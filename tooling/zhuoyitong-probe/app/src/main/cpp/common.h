#ifndef COMMON_H
#define COMMON_H
#include <stddef.h>

typedef struct { char *p; size_t n, cap; } sb_t;
void sb_init(sb_t *s);
void sb_kv(sb_t *s, const char *key, const char *fmt, ...);
void sb_free(sb_t *s);

void probe_set_progress(const char *path);
extern int g_socket_blocked;

void suite_syscalls(sb_t *s);                 /* fork 子进程逐项 syscall */
void suite_threads(sb_t *s);                  /* 4 线程计算 */
void suite_loopback(sb_t *s);                 /* bind/listen/connect/accept */
void suite_tls(sb_t *s, const char *host);    /* 裸 socket 发 ClientHello 读 ServerHello */
void suite_tcp(sb_t *s, const char *tag, const char *ip, int port);
void suite_udp(sb_t *s, const char *tag, const char *ip, int port);
void suite_ifaddrs(sb_t *s);
void suite_seccomp(sb_t *s);
/* fork+execve，捕获 stdout，返回子进程状态描述 */
void run_exec(sb_t *s, const char *key, const char *path, char *const argv[]);
/* 静态 ELF 逐 syscall 号扫描 */
void sweep_syscalls(sb_t *s, const char *elf_path, int from, int to);
void sweep_fork(sb_t *s, int from, int to);
#endif
