#include <string.h>
#include "common.h"
#include <jni.h>
#include <stdlib.h>

static jstring ret(JNIEnv *e, sb_t *s) {
    jstring j = (*e)->NewStringUTF(e, s->p ? s->p : "");
    sb_free(s);
    return j;
}
JNIEXPORT void JNICALL Java_probe_zyt_Native_init(JNIEnv *e, jclass c, jstring p) {
    const char *u = (*e)->GetStringUTFChars(e, p, 0);
    probe_set_progress(u);
    (*e)->ReleaseStringUTFChars(e, p, u);
}
JNIEXPORT jstring JNICALL Java_probe_zyt_Native_suite(JNIEnv *e, jclass c) {
    sb_t s; sb_init(&s);
    suite_seccomp(&s);
    suite_syscalls(&s);
    suite_threads(&s);
    suite_loopback(&s);
    suite_tls(&s, "api.deepseek.com");
    return ret(e, &s);
}
JNIEXPORT jboolean JNICALL Java_probe_zyt_Native_socketBlocked(JNIEnv *e, jclass c) { return g_socket_blocked; }
JNIEXPORT jstring JNICALL Java_probe_zyt_Native_net(JNIEnv *e, jclass c) {
    sb_t s; sb_init(&s);
    suite_ifaddrs(&s);
    suite_tcp(&s, "", "192.168.31.226", 46225);
    suite_tcp(&s, "", "127.0.0.1", 46225);
    suite_tcp(&s, "", "192.168.31.226", 40251);
    suite_tcp(&s, "", "192.168.31.226", 33917);
    suite_tcp(&s, "", "192.168.31.226", 62110);
    suite_tcp(&s, "", "192.168.31.226", 39347);
    suite_udp(&s, "", "255.255.255.255", 8710);
    suite_udp(&s, "", "192.168.31.255", 8710);
    return ret(e, &s);
}
JNIEXPORT jstring JNICALL Java_probe_zyt_Native_sweep(JNIEnv *e, jclass c, jstring p, jint from, jint to) {
    sb_t s; sb_init(&s);
    const char *u = (*e)->GetStringUTFChars(e, p, 0);
    sweep_syscalls(&s, u, from, to);
    (*e)->ReleaseStringUTFChars(e, p, u);
    return ret(e, &s);
}
/* args: path, argv...（argv[0] 另给）；返回 "key\tvalue\n" */
JNIEXPORT jstring JNICALL Java_probe_zyt_Native_exec(JNIEnv *e, jclass c, jstring key, jstring path, jobjectArray args) {
    sb_t s; sb_init(&s);
    const char *k = (*e)->GetStringUTFChars(e, key, 0);
    const char *p = (*e)->GetStringUTFChars(e, path, 0);
    int n = (*e)->GetArrayLength(e, args);
    char **argv = calloc(n + 1, sizeof *argv);
    for (int i = 0; i < n; i++) {
        jstring a = (*e)->GetObjectArrayElement(e, args, i);
        const char *u = (*e)->GetStringUTFChars(e, a, 0);
        argv[i] = strdup(u);
        (*e)->ReleaseStringUTFChars(e, a, u);
    }
    run_exec(&s, k, p, argv);
    for (int i = 0; i < n; i++) free(argv[i]);
    free(argv);
    (*e)->ReleaseStringUTFChars(e, key, k);
    (*e)->ReleaseStringUTFChars(e, path, p);
    return ret(e, &s);
}
JNIEXPORT jstring JNICALL Java_probe_zyt_Native_sweepFork(JNIEnv *e, jclass c, jint from, jint to) {
    sb_t s; sb_init(&s);
    sweep_fork(&s, from, to);
    return ret(e, &s);
}
