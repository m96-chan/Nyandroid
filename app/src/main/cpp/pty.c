/*
 * Native PTY helper for Nyandroid.
 *
 * Spawns a child process attached to a pseudo-terminal and exposes the
 * master fd to the JVM. This is the lowest layer of the LocalPtyBackend;
 * everything above it (VT parsing, rendering) is pure Kotlin.
 *
 * Bionic exposes forkpty()/openpty() via <pty.h> on modern Android, so we
 * do not need to open /dev/ptmx and juggle grantpt/unlockpt by hand.
 */
#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <android/log.h>

#define LOG_TAG "NyandroidPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char **dup_string_array(JNIEnv *env, jobjectArray arr, int append_null) {
    if (arr == NULL) {
        char **out = (char **) calloc(1, sizeof(char *));
        return out;
    }
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = (char **) calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char *cs = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(cs);
        (*env)->ReleaseStringUTFChars(env, s, cs);
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    (void) append_null;
    return out;
}

static void free_string_array(char **arr) {
    if (arr == NULL) return;
    for (char **p = arr; *p != NULL; p++) free(*p);
    free(arr);
}

/*
 * Returns int[2] = { masterFd, childPid } on success, or null on failure.
 */
JNIEXPORT jintArray JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeCreate(
        JNIEnv *env, jclass clazz,
        jstring jExe, jobjectArray jArgv, jobjectArray jEnv,
        jint cols, jint rows) {
    (void) clazz;

    const char *exe = (*env)->GetStringUTFChars(env, jExe, NULL);
    char **argv = dup_string_array(env, jArgv, 1);
    char **envp = dup_string_array(env, jEnv, 1);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    int master_fd = -1;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        (*env)->ReleaseStringUTFChars(env, jExe, exe);
        free_string_array(argv);
        free_string_array(envp);
        return NULL;
    }

    if (pid == 0) {
        /* Child: new session leader with the slave as controlling tty. */
        if (envp != NULL && envp[0] != NULL) {
            for (char **p = envp; *p != NULL; p++) {
                char *eq = strchr(*p, '=');
                if (eq == NULL) continue;
                *eq = '\0';
                setenv(*p, eq + 1, 1);
                *eq = '=';
            }
        }
        execvp(exe, argv);
        /* execvp only returns on failure. */
        _exit(127);
    }

    /* Parent. */
    (*env)->ReleaseStringUTFChars(env, jExe, exe);
    free_string_array(argv);
    free_string_array(envp);

    /* Non-blocking is handled on the Kotlin side via a dedicated reader
       thread; keep the fd blocking here for simple read() semantics. */
    fcntl(master_fd, F_SETFD, FD_CLOEXEC);

    jintArray result = (*env)->NewIntArray(env, 2);
    if (result == NULL) return NULL;
    jint vals[2] = {master_fd, (jint) pid};
    (*env)->SetIntArrayRegion(env, result, 0, 2, vals);
    return result;
}

JNIEXPORT jint JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeRead(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint offset, jint len) {
    (void) clazz;
    if (len <= 0) return 0;
    jbyte *stack = (jbyte *) malloc((size_t) len);
    if (stack == NULL) return -1;
    ssize_t n;
    do {
        n = read(fd, stack, (size_t) len);
    } while (n < 0 && errno == EINTR);
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buf, offset, (jsize) n, stack);
    }
    free(stack);
    if (n < 0) return -1;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeWrite(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray buf, jint offset, jint len) {
    (void) clazz;
    if (len <= 0) return 0;
    jbyte *data = (jbyte *) malloc((size_t) len);
    if (data == NULL) return -1;
    (*env)->GetByteArrayRegion(env, buf, offset, len, data);
    ssize_t total = 0;
    while (total < len) {
        ssize_t n = write(fd, data + total, (size_t) (len - total));
        if (n < 0) {
            if (errno == EINTR) continue;
            free(data);
            return -1;
        }
        total += n;
    }
    free(data);
    return (jint) total;
}

JNIEXPORT void JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeResize(
        JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows, jint pxWidth, jint pxHeight) {
    (void) env;
    (void) clazz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_xpixel = (unsigned short) (pxWidth > 0 ? pxWidth : 0);
    ws.ws_ypixel = (unsigned short) (pxHeight > 0 ? pxHeight : 0);
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeWaitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t r;
    do {
        r = waitpid((pid_t) pid, &status, 0);
    } while (r < 0 && errno == EINTR);
    if (r < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeClose(
        JNIEnv *env, jclass clazz, jint fd) {
    (void) env;
    (void) clazz;
    if (fd >= 0) close(fd);
}

JNIEXPORT void JNICALL
Java_dev_nyandroid_terminal_backend_Pty_nativeSendSignal(
        JNIEnv *env, jclass clazz, jint pid, jint sig) {
    (void) env;
    (void) clazz;
    if (pid > 0) kill((pid_t) pid, sig);
}
