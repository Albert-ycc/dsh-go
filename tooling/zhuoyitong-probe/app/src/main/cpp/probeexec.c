/* 作为 nativeLibraryDir 下的可执行文件被 exec：在 exec 出来的 bionic 动态进程里重复一遍探针 */
#include "common.h"
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    sb_t s; sb_init(&s);
    const char *mode = argc > 1 ? argv[1] : "hello";
    if (!strcmp(mode, "suite")) {
        suite_syscalls(&s);
        suite_threads(&s);
        suite_loopback(&s);
        suite_tls(&s, "api.deepseek.com");
    } else if (!strcmp(mode, "thread")) suite_threads(&s);
    else if (!strcmp(mode, "net")) { suite_loopback(&s); suite_tls(&s, "api.deepseek.com"); }
    else sb_kv(&s, "probeexec", "hello pid=%d uid=%d", getpid(), getuid());
    if (s.p) {
        FILE *f = argc > 2 ? fopen(argv[2], "w") : stdout;
        if (f) { fputs(s.p, f); if (f != stdout) fclose(f); }
    }
    return 0;
}
