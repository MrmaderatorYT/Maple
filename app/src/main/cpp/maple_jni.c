/*
 * Maple native helpers.
 *
 *  - spawn():      fork/exec a QEMU binary while keeping selected file descriptors open in the
 *                  child (java.lang.ProcessBuilder closes everything, and we need to hand
 *                  Storage Access Framework descriptors to QEMU through -add-fd).
 *  - waitFor()/kill(): process lifetime.
 *  - setAffinity(): pin a vCPU thread of the child to a set of host cores.
 *  - blit():       copy an RFB raw rectangle (R,G,B,X bytes) into an ARGB_8888 bitmap.
 */
#define _GNU_SOURCE /* cpu_set_t, sched_setaffinity */
#include <jni.h>
#include <android/bitmap.h>
#include <android/fdsan.h>
#include <android/log.h>

#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define TAG "maple-native"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static char **to_cstrv(JNIEnv *env, jobjectArray arr) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **v = calloc((size_t) n + 1, sizeof(char *));
    if (!v) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        v[i] = strdup(c ? c : "");
        if (c) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    v[n] = NULL;
    return v;
}

static void free_cstrv(char **v) {
    if (!v) return;
    for (char **p = v; *p; p++) free(*p);
    free(v);
}

JNIEXPORT jint JNICALL
Java_com_ccs_maple_qemu_NativeBridge_spawn(JNIEnv *env, jclass clazz,
                                                        jstring jexe, jobjectArray jargv,
                                                        jobjectArray jenvp, jintArray jkeep,
                                                        jstring jlog, jstring jcwd) {
    const char *exe = (*env)->GetStringUTFChars(env, jexe, NULL);
    const char *logp = (*env)->GetStringUTFChars(env, jlog, NULL);
    const char *cwd = (*env)->GetStringUTFChars(env, jcwd, NULL);
    char **argv = to_cstrv(env, jargv);
    char **envp = to_cstrv(env, jenvp);

    jsize nkeep = jkeep ? (*env)->GetArrayLength(env, jkeep) : 0;
    jint *keep = nkeep ? (*env)->GetIntArrayElements(env, jkeep, NULL) : NULL;

    /* Everything the child needs is prepared before fork(): only async-signal-safe calls after. */
    int logfd = open(logp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    int nullfd = open("/dev/null", O_RDONLY | O_CLOEXEC);
    long maxfd = sysconf(_SC_OPEN_MAX);
    if (maxfd < 0 || maxfd > 65536) maxfd = 65536;

    pid_t pid = -1;
    if (argv && envp && logfd >= 0 && nullfd >= 0) {
        pid = fork();
        if (pid == 0) {
            /* ART tags its descriptors; closing them below would otherwise abort the child. */
            android_fdsan_set_error_level(ANDROID_FDSAN_ERROR_LEVEL_DISABLED);
            dup2(nullfd, 0);
            dup2(logfd, 1);
            dup2(logfd, 2);
            for (long fd = 3; fd < maxfd; fd++) {
                int wanted = 0;
                for (jsize i = 0; i < nkeep; i++) {
                    if (keep[i] == fd) {
                        wanted = 1;
                        break;
                    }
                }
                if (wanted) {
                    int fl = fcntl((int) fd, F_GETFD);
                    if (fl >= 0) fcntl((int) fd, F_SETFD, fl & ~FD_CLOEXEC);
                } else {
                    close((int) fd);
                }
            }
            if (chdir(cwd) != 0) { /* not fatal */ }
            /* The app runs with SIGPIPE etc. masked by ART; give the child a clean slate. */
            sigset_t empty;
            sigemptyset(&empty);
            sigprocmask(SIG_SETMASK, &empty, NULL);
            execve(exe, argv, envp);
            static const char msg[] = "maple: execve failed\n";
            if (write(2, msg, sizeof(msg) - 1) < 0) { /* ignore */ }
            _exit(127);
        }
        if (pid < 0) LOGE("fork failed: %s", strerror(errno));
    } else {
        LOGE("spawn setup failed: %s", strerror(errno));
    }

    if (logfd >= 0) close(logfd);
    if (nullfd >= 0) close(nullfd);
    if (keep) (*env)->ReleaseIntArrayElements(env, jkeep, keep, JNI_ABORT);
    free_cstrv(argv);
    free_cstrv(envp);
    (*env)->ReleaseStringUTFChars(env, jexe, exe);
    (*env)->ReleaseStringUTFChars(env, jlog, logp);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
    return (jint) pid;
}

/* Blocks until the child exits. Returns exit code, or 128+signal, or -1 on error. */
JNIEXPORT jint JNICALL
Java_com_ccs_maple_qemu_NativeBridge_waitFor(JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_ccs_maple_qemu_NativeBridge_kill(JNIEnv *env, jclass clazz, jint pid,
                                                       jint sig) {
    return kill((pid_t) pid, sig) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_ccs_maple_qemu_NativeBridge_setAffinity(JNIEnv *env, jclass clazz, jint tid,
                                                              jlong mask) {
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int i = 0; i < 64; i++) {
        if (mask & (1LL << i)) CPU_SET(i, &set);
    }
    return sched_setaffinity((pid_t) tid, sizeof(set), &set) == 0 ? 0 : -errno;
}

JNIEXPORT jint JNICALL
Java_com_ccs_maple_qemu_NativeBridge_setNice(JNIEnv *env, jclass clazz, jint tid,
                                                          jint nice) {
    return setpriority(PRIO_PROCESS, (id_t) tid, nice) == 0 ? 0 : -errno;
}

/*
 * src holds w*h pixels as R,G,B,X bytes (the RFB pixel format we negotiate), which is the
 * in-memory layout of ANDROID_BITMAP_FORMAT_RGBA_8888 apart from the alpha byte.
 */
JNIEXPORT jboolean JNICALL
Java_com_ccs_maple_qemu_NativeBridge_blit(JNIEnv *env, jclass clazz, jobject bitmap,
                                                       jbyteArray jsrc, jint x, jint y, jint w,
                                                       jint h) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (x < 0 || y < 0 || w <= 0 || h <= 0) return JNI_FALSE;
    if ((uint32_t) x + (uint32_t) w > info.width || (uint32_t) y + (uint32_t) h > info.height)
        return JNI_FALSE;
    if ((*env)->GetArrayLength(env, jsrc) < (jsize) (w * h * 4)) return JNI_FALSE;

    void *pixels = NULL;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        return JNI_FALSE;
    uint8_t *src = (*env)->GetPrimitiveArrayCritical(env, jsrc, NULL);
    if (src) {
        for (int row = 0; row < h; row++) {
            const uint32_t *s = (const uint32_t *) (src + (size_t) row * (size_t) w * 4);
            uint32_t *d = (uint32_t *) ((uint8_t *) pixels + (size_t) (y + row) * info.stride) + x;
            for (int col = 0; col < w; col++) {
                d[col] = s[col] | 0xFF000000u; /* little endian: alpha is the top byte */
            }
        }
        (*env)->ReleasePrimitiveArrayCritical(env, jsrc, src, JNI_ABORT);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return src ? JNI_TRUE : JNI_FALSE;
}
