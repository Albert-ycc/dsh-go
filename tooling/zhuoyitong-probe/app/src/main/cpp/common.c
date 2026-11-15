#define _GNU_SOURCE
#include "common.h"
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <netdb.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

int g_socket_blocked = 0;
static char g_progress[512];

/* ---------- 字符串缓冲 ---------- */
void sb_init(sb_t *s) { s->p = NULL; s->n = s->cap = 0; }
void sb_free(sb_t *s) { free(s->p); sb_init(s); }
static void sb_add(sb_t *s, const char *t, size_t l) {
    if (s->n + l + 1 > s->cap) {
        s->cap = (s->n + l + 1) * 2;
        s->p = realloc(s->p, s->cap);
    }
    memcpy(s->p + s->n, t, l);
    s->n += l;
    s->p[s->n] = 0;
}
void sb_kv(sb_t *s, const char *key, const char *fmt, ...) {
    char buf[12288];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    for (char *c = buf; *c; c++) if (*c == '\n' || *c == '\t') *c = ' ';
    sb_add(s, key, strlen(key));
    sb_add(s, "\t", 1);
    sb_add(s, buf, strlen(buf));
    sb_add(s, "\n", 1);
}

/* ---------- 进度日志：被 SIGSYS 杀掉时能看出停在哪一项 ---------- */
void probe_set_progress(const char *path) { snprintf(g_progress, sizeof g_progress, "%s", path); }
static void plog(const char *msg) {
    if (!g_progress[0]) return;
    int fd = open(g_progress, O_WRONLY | O_CREAT | O_APPEND, 0644);
    if (fd < 0) return;
    write(fd, msg, strlen(msg));
    write(fd, "\n", 1);
    fsync(fd);
    close(fd);
}

static long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static const char *errname(int e) {
    switch (e) {
    case 0: return "OK"; case EPERM: return "EPERM"; case ENOENT: return "ENOENT";
    case ESRCH: return "ESRCH"; case EINTR: return "EINTR"; case EIO: return "EIO";
    case EBADF: return "EBADF"; case ECHILD: return "ECHILD"; case EAGAIN: return "EAGAIN";
    case ENOMEM: return "ENOMEM"; case EACCES: return "EACCES"; case EFAULT: return "EFAULT";
    case EBUSY: return "EBUSY"; case EEXIST: return "EEXIST"; case ENODEV: return "ENODEV";
    case ENOTDIR: return "ENOTDIR"; case EISDIR: return "EISDIR"; case EINVAL: return "EINVAL";
    case ENFILE: return "ENFILE"; case EMFILE: return "EMFILE"; case ENOTTY: return "ENOTTY";
    case ETXTBSY: return "ETXTBSY"; case ESPIPE: return "ESPIPE"; case EROFS: return "EROFS";
    case EPIPE: return "EPIPE"; case ERANGE: return "ERANGE"; case ENOSYS: return "ENOSYS";
    case ENOTSOCK: return "ENOTSOCK"; case EOPNOTSUPP: return "EOPNOTSUPP";
    case EAFNOSUPPORT: return "EAFNOSUPPORT"; case EPROTONOSUPPORT: return "EPROTONOSUPPORT";
    case ENETUNREACH: return "ENETUNREACH"; case ECONNREFUSED: return "ECONNREFUSED";
    case ETIMEDOUT: return "ETIMEDOUT"; case EHOSTUNREACH: return "EHOSTUNREACH";
    case EADDRNOTAVAIL: return "EADDRNOTAVAIL";
    default: return "E?";
    }
}

/* ---------- fork 子进程里跑单个 syscall ---------- */
struct shm { volatile int done; volatile long ret; volatile int err; };
typedef long (*cfn)(void);

static struct shm *shm_new(void) {
    return mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED | MAP_ANONYMOUS, -1, 0);
}

/* 返回 1 正常回收；0 超时（已 SIGKILL 回收） */
static int wait_timeout(pid_t pid, int ms, int *status) {
    long t0 = now_ms();
    for (;;) {
        pid_t r = waitpid(pid, status, WNOHANG);
        if (r == pid) return 1;
        if (r < 0) { *status = 0; return 1; }
        if (now_ms() - t0 > ms) {
            kill(pid, SIGKILL);
            waitpid(pid, status, 0);
            return 0;
        }
        usleep(2000);
    }
}

static void fmt_child(char *out, size_t n, pid_t pid, int ok, int status, struct shm *m) {
    if (pid < 0) { snprintf(out, n, "FORK_FAIL errno=%d(%s)", errno, errname(errno)); return; }
    if (!ok) { snprintf(out, n, "TIMEOUT(阻塞中,未被杀)"); return; }
    if (WIFSIGNALED(status)) {
        int sg = WTERMSIG(status);
        snprintf(out, n, "KILLED sig=%d(%s)", sg, sg == SIGSYS ? "SIGSYS" : sg == SIGKILL ? "SIGKILL" : sg == SIGSEGV ? "SIGSEGV" : strsignal(sg));
        return;
    }
    if (m && m->done) {
        if (m->ret == -1) snprintf(out, n, "ret=-1 errno=%d(%s)", m->err, errname(m->err));
        else snprintf(out, n, "ret=%ld", m->ret);
        return;
    }
    snprintf(out, n, "exit=%d", WEXITSTATUS(status));
}

static void run_in_child(sb_t *s, const char *key, cfn fn, int *sigsys) {
    char msg[128], res[160];
    snprintf(msg, sizeof msg, "START %s", key);
    plog(msg);
    struct shm *m = shm_new();
    m->done = 0;
    pid_t pid = fork();
    if (pid == 0) {
        long r = fn();
        m->err = errno;
        m->ret = r;
        m->done = 1;
        _exit(0);
    }
    int st = 0, ok = 0;
    if (pid > 0) ok = wait_timeout(pid, 3000, &st);
    fmt_child(res, sizeof res, pid, ok, st, m);
    if (sigsys) *sigsys = (pid > 0 && ok && WIFSIGNALED(st));
    sb_kv(s, key, "%s", res);
    snprintf(msg, sizeof msg, "DONE %s %s", key, res);
    plog(msg);
    munmap(m, 4096);
}

#define SC(nm, ...) static long c_##nm(void) { return (long)(__VA_ARGS__); }
static unsigned char g_buf[256];
static int g_fds[2];
SC(clone3, syscall(435, NULL, 0))
SC(set_robust_list, syscall(99, NULL, 0))
SC(rseq, syscall(293, NULL, 0, 0, 0))
SC(socket, ({ long r = syscall(198, AF_INET, SOCK_STREAM, 0); if (r >= 0) close((int)r); r; }))
SC(socketpair, syscall(199, AF_UNIX, SOCK_STREAM, 0, g_fds))
SC(accept4, syscall(242, -1, NULL, NULL, 0))
SC(ptrace_traceme, syscall(117, 0, 0, 0, 0))
SC(io_uring_setup, syscall(425, 1, g_buf))
SC(openat2, ({ unsigned long long how[3] = {0, 0, 0}; syscall(437, AT_FDCWD, "/proc/self/status", how, 24); }))
SC(faccessat2, syscall(439, AT_FDCWD, "/", 0, 0))
SC(pipe2, syscall(59, g_fds, 0))
SC(eventfd2, syscall(19, 0, 0))
SC(epoll_create1, syscall(20, 0))
SC(memfd_create, syscall(279, "p", 0))
SC(getrandom, syscall(278, g_buf, 8, 0))
static long c_execve(void) {
    char *argv[] = {"sh", "-c", "exit 7", NULL};
    char *envp[] = {NULL};
    return syscall(221, "/system/bin/sh", argv, envp);
}
static void *noop_thread(void *a) { return a; }
static long c_pthread(void) {
    pthread_t t;
    int rc = pthread_create(&t, NULL, noop_thread, NULL);
    if (rc == 0) pthread_join(t, NULL);
    errno = rc;
    return rc == 0 ? 0 : -1;
}
SC(fork_in_child, ({ pid_t p = fork(); if (p == 0) _exit(0); if (p > 0) waitpid(p, NULL, 0); p > 0 ? 0 : -1; }))

void suite_syscalls(sb_t *s) {
    static const struct { const char *k; cfn f; } items[] = {
        {"clone3(435)", c_clone3}, {"set_robust_list(99)", c_set_robust_list},
        {"rseq(293)", c_rseq}, {"socket(198)", c_socket}, {"socketpair(199)", c_socketpair},
        {"accept4(242)", c_accept4}, {"ptrace(117)TRACEME", c_ptrace_traceme},
        {"io_uring_setup(425)", c_io_uring_setup}, {"openat2(437)", c_openat2},
        {"faccessat2(439)", c_faccessat2}, {"pipe2(59)", c_pipe2}, {"eventfd2(19)", c_eventfd2},
        {"epoll_create1(20)", c_epoll_create1}, {"memfd_create(279)", c_memfd_create},
        {"getrandom(278)", c_getrandom}, {"execve(221)sh-exit7", c_execve},
        {"pthread_create@fork子", c_pthread}, {"fork@fork子", c_fork_in_child},
    };
    for (size_t i = 0; i < sizeof items / sizeof *items; i++) {
        int sg = 0;
        run_in_child(s, items[i].k, items[i].f, &sg);
        if (sg && strncmp(items[i].k, "socket(", 7) == 0) g_socket_blocked = 1;
    }
}

/* ---------- 线程 ---------- */
static void *work(void *a) {
    unsigned long long x = 0;
    for (unsigned long long i = 1; i < 20000000ULL; i++) x += i * i % 7;
    *(unsigned long long *)a = x;
    return NULL;
}
void suite_threads(sb_t *s) {
    plog("START threads4");
    pthread_t t[4];
    unsigned long long r[4] = {0};
    long t0 = now_ms();
    int rc = 0, made = 0;
    for (int i = 0; i < 4; i++) {
        rc = pthread_create(&t[i], NULL, work, &r[i]);
        if (rc) break;
        made++;
    }
    for (int i = 0; i < made; i++) pthread_join(t[i], NULL);
    sb_kv(s, "pthread_create x4", "created=%d rc=%d(%s) %ldms sum_ok=%d", made, rc, errname(rc), now_ms() - t0,
          made == 4 && r[0] == r[3] && r[0] != 0);
    plog("DONE threads4");
}

/* ---------- 网络 ---------- */
static int connect_to(int fd, struct sockaddr *sa, socklen_t sl, int ms, int *err) {
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
    int r = connect(fd, sa, sl);
    if (r == 0) { *err = 0; return 0; }
    if (errno != EINPROGRESS) { *err = errno; return -1; }
    struct pollfd p = {fd, POLLOUT, 0};
    int pr = poll(&p, 1, ms);
    if (pr == 0) { *err = ETIMEDOUT; return -2; }
    int e = 0; socklen_t el = sizeof e;
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &e, &el);
    *err = e;
    return e ? -1 : 0;
}

void suite_loopback(sb_t *s) {
    if (g_socket_blocked) { sb_kv(s, "loopback bind/listen/connect/accept", "SKIPPED(socket 被杀)"); return; }
    plog("START loopback");
    int ls = socket(AF_INET, SOCK_STREAM, 0);
    if (ls < 0) { sb_kv(s, "loopback bind/listen/connect/accept", "socket errno=%d(%s)", errno, errname(errno)); return; }
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
    socklen_t al = sizeof a;
    if (bind(ls, (void *)&a, sizeof a) < 0) { sb_kv(s, "loopback bind", "errno=%d(%s)", errno, errname(errno)); close(ls); return; }
    getsockname(ls, (void *)&a, &al);
    int port = ntohs(a.sin_port);
    if (listen(ls, 4) < 0) { sb_kv(s, "loopback listen", "errno=%d(%s)", errno, errname(errno)); close(ls); return; }
    int cs = socket(AF_INET, SOCK_STREAM, 0), err = 0;
    int cr = connect_to(cs, (void *)&a, sizeof a, 2000, &err);
    int as = accept(ls, NULL, NULL);
    int ae = errno;
    char b[8] = {0};
    int got = -1;
    if (cr == 0 && as >= 0) { write(cs, "ping", 4); got = (int)read(as, b, 7); }
    sb_kv(s, "loopback bind/listen/connect/accept", "port=%d connect=%d(%s) accept=%d%s%s echo=%d", port, cr, errname(err),
          as, as < 0 ? "(errno " : "", as < 0 ? errname(ae) : "", got);
    close(cs); if (as >= 0) close(as); close(ls);
    /* UDP 回环 */
    int u = socket(AF_INET, SOCK_DGRAM, 0);
    if (u < 0) sb_kv(s, "udp loopback", "socket errno=%d(%s)", errno, errname(errno));
    else {
        struct sockaddr_in ua = {.sin_family = AF_INET, .sin_addr.s_addr = htonl(INADDR_LOOPBACK)};
        socklen_t ul = sizeof ua;
        int br = bind(u, (void *)&ua, sizeof ua);
        getsockname(u, (void *)&ua, &ul);
        int sr = (int)sendto(u, "x", 1, 0, (void *)&ua, sizeof ua);
        struct pollfd p = {u, POLLIN, 0};
        int pr = poll(&p, 1, 1000);
        sb_kv(s, "udp loopback", "bind=%d send=%d recv=%d", br, sr, pr);
        close(u);
    }
    plog("DONE loopback");
}

void suite_tcp(sb_t *s, const char *tag, const char *ip, int port) {
    char key[96];
    snprintf(key, sizeof key, "native connect %s:%d", ip, port);
    (void)tag;
    if (g_socket_blocked) { sb_kv(s, key, "SKIPPED(socket 被杀)"); return; }
    plog(key);
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { sb_kv(s, key, "socket errno=%d(%s)", errno, errname(errno)); return; }
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(port)};
    inet_pton(AF_INET, ip, &a.sin_addr);
    int err = 0;
    long t0 = now_ms();
    int r = connect_to(fd, (void *)&a, sizeof a, 4000, &err);
    sb_kv(s, key, "%s errno=%d(%s) %ldms", r == 0 ? "CONNECTED" : r == -2 ? "TIMEOUT" : "FAIL", err, errname(err), now_ms() - t0);
    close(fd);
}

void suite_udp(sb_t *s, const char *tag, const char *ip, int port) {
    char key[96];
    snprintf(key, sizeof key, "udp bcast %s:%d", ip, port);
    (void)tag;
    if (g_socket_blocked) { sb_kv(s, key, "SKIPPED(socket 被杀)"); return; }
    plog(key);
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) { sb_kv(s, key, "socket errno=%d(%s)", errno, errname(errno)); return; }
    int one = 1;
    int so = setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &one, sizeof one);
    struct sockaddr_in a = {.sin_family = AF_INET, .sin_port = htons(port)};
    inet_pton(AF_INET, ip, &a.sin_addr);
    int sr = (int)sendto(fd, "OHOS HDC", 8, 0, (void *)&a, sizeof a);
    int se = errno;
    char b[128] = {0};
    struct pollfd p = {fd, POLLIN, 0};
    int pr = poll(&p, 1, 2000);
    int n = 0;
    if (pr > 0) n = (int)recv(fd, b, sizeof b - 1, 0);
    if (sr < 0) sb_kv(s, key, "sendto errno=%d(%s) setsockopt=%d", se, errname(se), so);
    else sb_kv(s, key, "sent=%d reply=%s%s", sr, n > 0 ? b : "无", n > 0 ? "" : "(2s)");
    close(fd);
}

void suite_ifaddrs(sb_t *s) {
    struct ifaddrs *ifa;
    if (getifaddrs(&ifa) < 0) { sb_kv(s, "getifaddrs", "FAIL errno=%d(%s)", errno, errname(errno)); return; }
    char line[400] = "";
    for (struct ifaddrs *p = ifa; p; p = p->ifa_next) {
        if (p->ifa_addr && p->ifa_addr->sa_family == AF_INET) {
            char ip[32];
            inet_ntop(AF_INET, &((struct sockaddr_in *)p->ifa_addr)->sin_addr, ip, sizeof ip);
            size_t l = strlen(line);
            snprintf(line + l, sizeof line - l, "%s=%s ", p->ifa_name, ip);
        }
    }
    freeifaddrs(ifa);
    sb_kv(s, "getifaddrs(IPv4)", "%s", line[0] ? line : "无IPv4");
}

void suite_seccomp(sb_t *s) {
    int r = prctl(PR_GET_SECCOMP);
    sb_kv(s, "prctl(PR_GET_SECCOMP)", "%d (0无/2filter) errno=%d", r, r < 0 ? errno : 0);
}

/* 裸 socket TLS：发 ClientHello，收 ServerHello，不做密钥交换 */
static size_t u16(unsigned char *p, unsigned v) { p[0] = v >> 8; p[1] = v; return 2; }
static size_t build_hello(unsigned char *o, const char *host) {
    unsigned char *p = o + 9; /* 5 字节记录头 + 4 字节握手头 */
    *p++ = 3; *p++ = 3;
    for (int i = 0; i < 32; i++) *p++ = (unsigned char)(rand() & 0xff);
    *p++ = 0;                              /* session_id 空 */
    p += u16(p, 4); p += u16(p, 0x1301); p += u16(p, 0x1302);
    *p++ = 1; *p++ = 0;
    unsigned char *extlen = p; p += 2;
    unsigned char *e0 = p;
    size_t hl = strlen(host);
    p += u16(p, 0); p += u16(p, hl + 5); p += u16(p, hl + 3); *p++ = 0; p += u16(p, hl);
    memcpy(p, host, hl); p += hl;
    p += u16(p, 0x000a); p += u16(p, 4); p += u16(p, 2); p += u16(p, 0x001d);
    p += u16(p, 0x000d); p += u16(p, 6); p += u16(p, 4); p += u16(p, 0x0804); p += u16(p, 0x0403);
    p += u16(p, 0x002b); p += u16(p, 3); *p++ = 2; p += u16(p, 0x0304);
    p += u16(p, 0x0033); p += u16(p, 38); p += u16(p, 36); p += u16(p, 0x001d); p += u16(p, 32);
    for (int i = 0; i < 32; i++) *p++ = (unsigned char)(rand() & 0xff);
    u16(extlen, p - e0);
    size_t hs = p - (o + 5);               /* 握手消息总长 */
    o[0] = 0x16; o[1] = 3; o[2] = 1; u16(o + 3, hs);
    o[5] = 1; o[6] = (hs - 4) >> 16; o[7] = (hs - 4) >> 8; o[8] = hs - 4;
    return p - o;
}
void suite_tls(sb_t *s, const char *host) {
    char key[96];
    snprintf(key, sizeof key, "native raw TLS %s:443", host);
    if (g_socket_blocked) { sb_kv(s, key, "SKIPPED(socket 被杀)"); return; }
    plog(key);
    srand((unsigned)time(NULL));
    struct addrinfo hints = {.ai_family = AF_INET, .ai_socktype = SOCK_STREAM}, *res = NULL;
    long t0 = now_ms();
    int gr = getaddrinfo(host, "443", &hints, &res);
    if (gr) { sb_kv(s, key, "getaddrinfo=%d(%s)", gr, gai_strerror(gr)); return; }
    char ip[32];
    inet_ntop(AF_INET, &((struct sockaddr_in *)res->ai_addr)->sin_addr, ip, sizeof ip);
    int fd = socket(AF_INET, SOCK_STREAM, 0), err = 0;
    int cr = connect_to(fd, res->ai_addr, res->ai_addrlen, 6000, &err);
    freeaddrinfo(res);
    if (cr) { sb_kv(s, key, "ip=%s connect FAIL errno=%d(%s)", ip, err, errname(err)); close(fd); return; }
    unsigned char hello[512];
    size_t hn = build_hello(hello, host);
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) & ~O_NONBLOCK);
    ssize_t w = send(fd, hello, hn, 0);
    unsigned char rb[64] = {0};
    struct pollfd p = {fd, POLLIN, 0};
    int pr = poll(&p, 1, 6000);
    ssize_t n = pr > 0 ? recv(fd, rb, sizeof rb, 0) : -1;
    const char *verdict = n >= 6 && rb[0] == 0x16 && rb[5] == 2 ? "ServerHello收到" : n >= 2 && rb[0] == 0x15 ? "收到Alert" : n > 0 ? "收到其它" : "无应答";
    sb_kv(s, key, "ip=%s sent=%d recv=%d %s %ldms", ip, (int)w, (int)n, verdict, now_ms() - t0);
    close(fd);
}

/* ---------- exec ---------- */
void run_exec(sb_t *s, const char *key, const char *path, char *const argv[]) {
    char msg[300];
    snprintf(msg, sizeof msg, "START exec %s", key);
    plog(msg);
    int pf[2];
    if (pipe(pf) < 0) { sb_kv(s, key, "pipe errno=%d", errno); return; }
    struct shm *m = shm_new();
    m->done = 0;
    pid_t pid = fork();
    if (pid == 0) {
        close(pf[0]);
        dup2(pf[1], 1); dup2(pf[1], 2);
        char *envp[] = {"PATH=/system/bin", NULL};
        execve(path, argv, envp);
        m->err = errno; m->done = 1;
        _exit(127);
    }
    close(pf[1]);
    static char out[12288];
    memset(out, 0, sizeof out);
    size_t on = 0;
    long t0 = now_ms();
    for (;;) {
        struct pollfd p = {pf[0], POLLIN, 0};
        int pr = poll(&p, 1, 200);
        if (pr > 0) {
            ssize_t r = read(pf[0], out + on, sizeof out - 1 - on);
            if (r <= 0) break;
            on += r;
            if (on >= sizeof out - 1) break;
        } else if (now_ms() - t0 > 6000) break;
        else if (pr == 0) { int st; if (waitpid(pid, &st, WNOHANG) == pid) { pid = -2; break; } }
    }
    close(pf[0]);
    int st = 0, ok = 1;
    char res[200];
    if (pid == -2) { sb_kv(s, key, "已退出 out=[%s]", out); munmap(m, 4096); return; }
    ok = wait_timeout(pid, 3000, &st);
    if (m->done) snprintf(res, sizeof res, "EXECVE_FAIL errno=%d(%s)", m->err, errname(m->err));
    else fmt_child(res, sizeof res, pid, ok, st, NULL);
    for (char *c = out; *c; c++) if (*c == '\n') *c = '|';
    sb_kv(s, key, "%s out=[%s]", res, out);
    munmap(m, 4096);
}

/* ---------- 静态 ELF 逐 syscall 扫描 ---------- */
static int unsafe_nr(int n) {
    /* 改系统状态的调用不跑：reboot kexec 模块 主机名 时钟 swap 挂载 chroot acct syslog vhangup seccomp bpf */
    static const int bad[] = {142, 104, 294, 105, 273, 106, 161, 162, 170, 112, 266, 171, 224, 225, 40, 39, 41, 51, 89, 116, 58, 277, 280};
    for (size_t i = 0; i < sizeof bad / sizeof *bad; i++) if (bad[i] == n) return 1;
    return 0;
}
static size_t make_elf(unsigned char *b, int nr) {
    memset(b, 0, 256);
    unsigned char eh[16] = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};
    memcpy(b, eh, 16);
    *(unsigned short *)(b + 16) = 2;          /* ET_EXEC */
    *(unsigned short *)(b + 18) = 183;        /* EM_AARCH64 */
    *(unsigned int *)(b + 20) = 1;
    *(unsigned long long *)(b + 24) = 0x400078;
    *(unsigned long long *)(b + 32) = 64;     /* phoff */
    *(unsigned short *)(b + 52) = 64;
    *(unsigned short *)(b + 54) = 56;
    *(unsigned short *)(b + 56) = 1;
    unsigned int code[16]; int c = 0;
    for (int r = 0; r <= 5; r++) code[c++] = 0xD2800000u | r;                 /* movz xr,#0 */
    code[c++] = 0xD2800000u | ((unsigned)nr << 5) | 8;                         /* movz x8,#nr */
    code[c++] = 0xD4000001u;                                                   /* svc #0 */
    code[c++] = 0xD2800000u | (93u << 5) | 8;                                  /* movz x8,#93 (exit) */
    code[c++] = 0xD4000001u;
    size_t total = 120 + c * 4;
    unsigned char *ph = b + 64;
    *(unsigned int *)(ph + 0) = 1;            /* PT_LOAD */
    *(unsigned int *)(ph + 4) = 5;            /* R+X */
    *(unsigned long long *)(ph + 16) = 0x400000;
    *(unsigned long long *)(ph + 24) = 0x400000;
    *(unsigned long long *)(ph + 32) = total;
    *(unsigned long long *)(ph + 40) = total;
    *(unsigned long long *)(ph + 48) = 0x1000;
    memcpy(b + 120, code, c * 4);
    return total;
}
void sweep_syscalls(sb_t *s, const char *elf, int from, int to) {
    for (int nr = from; nr <= to; nr++) {
        char key[16], res[96];
        snprintf(key, sizeof key, "%03d", nr);
        if (unsafe_nr(nr)) { sb_kv(s, key, "SKIP_UNSAFE"); continue; }
        unsigned char img[256];
        size_t n = make_elf(img, nr);
        unlink(elf);
        int fd = open(elf, O_WRONLY | O_CREAT | O_TRUNC, 0700);
        if (fd < 0) { sb_kv(s, key, "WRITE_FAIL errno=%d(%s)", errno, errname(errno)); return; }
        write(fd, img, n);
        close(fd);
        chmod(elf, 0700);
        struct shm *m = shm_new();
        m->done = 0;
        pid_t pid = fork();
        if (pid == 0) {
            struct rlimit rl = {0, 0};
            setrlimit(RLIMIT_CORE, &rl);
            int nul = open("/dev/null", O_RDWR);
            dup2(nul, 0); dup2(nul, 1); dup2(nul, 2);
            char *argv[] = {(char *)elf, NULL};
            char *envp[] = {NULL};
            execve(elf, argv, envp);
            m->err = errno; m->done = 1;
            _exit(127);
        }
        int st = 0, ok = pid > 0 ? wait_timeout(pid, 2500, &st) : 0;
        if (pid < 0) snprintf(res, sizeof res, "FORK_FAIL errno=%d", errno);
        else if (m->done) snprintf(res, sizeof res, "EXECVE_FAIL errno=%d(%s)", m->err, errname(m->err));
        else if (!ok) snprintf(res, sizeof res, "TIMEOUT");
        else if (WIFSIGNALED(st)) snprintf(res, sizeof res, "KILLED sig=%d", WTERMSIG(st));
        else {
            int e = WEXITSTATUS(st);
            if (e >= 134) snprintf(res, sizeof res, "exit=%d -%s", e, errname(256 - e));
            else snprintf(res, sizeof res, "exit=%d", e);
        }
        sb_kv(s, key, "%s", res);
        munmap(m, 4096);
    }
    unlink(elf);
}

/* fork 不 exec：子进程继承 App 进程自己的过滤器，用来和 exec 静态 ELF 的结果对账 */
void sweep_fork(sb_t *s, int from, int to) {
    for (int nr = from; nr <= to; nr++) {
        char key[16], res[96];
        snprintf(key, sizeof key, "%03d", nr);
        if (unsafe_nr(nr)) { sb_kv(s, key, "SKIP_UNSAFE"); continue; }
        struct shm *m = shm_new();
        m->done = 0;
        pid_t pid = fork();
        if (pid == 0) {
            struct rlimit rl = {0, 0};
            setrlimit(RLIMIT_CORE, &rl);
            int nul = open("/dev/null", O_RDWR);
            dup2(nul, 0); dup2(nul, 1); dup2(nul, 2);
            long r = syscall(nr, 0, 0, 0, 0, 0, 0);
            _exit((int)(r < 0 ? 256 + (-errno) : r) & 0xff);
        }
        int st = 0, ok = pid > 0 ? wait_timeout(pid, 2500, &st) : 0;
        if (pid < 0) snprintf(res, sizeof res, "FORK_FAIL errno=%d", errno);
        else if (!ok) snprintf(res, sizeof res, "TIMEOUT");
        else if (WIFSIGNALED(st)) snprintf(res, sizeof res, "KILLED sig=%d", WTERMSIG(st));
        else {
            int e = WEXITSTATUS(st);
            if (e >= 134) snprintf(res, sizeof res, "exit=%d -%s", e, errname(256 - e));
            else snprintf(res, sizeof res, "exit=%d", e);
        }
        sb_kv(s, key, "%s", res);
        munmap(m, 4096);
    }
}
