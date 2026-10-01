/*
 * NativeTerminalJNI.c — PTY-backed terminal session bridge for OpenCode Term.
 *
 * Contract (mirrored by ai.opencode.term.native.NativeTerminal):
 *   native long  nativeCreateSession()                       -> session handle or 0
 *   native int   nativeStart(long h, String shell, String cwd,
 *                            String[] cmd, String[] env)     -> 0 or -errno
 *   native int   nativeRead(long h, byte[] buf)               -> n>0, 0=EOF, -errno
 *   native int   nativeWrite(long h, byte[] buf, int len)     -> n or -errno
 *   native int   nativeResize(long h, int rows, int cols)     -> 0 or -errno
 *   native void  nativeSendSignal(long h, int sig)
 *   native void  nativeCloseSession(long h)
 *   native int   nativeWaitFor(long h, int timeoutMs)         -> exit code, -1 if still running,
 *                                                               -2 on timeout, -errno on error
 *
 * Threading: one reader thread owns nativeRead; the caller serializes writes.
 * All functions validate the handle and report errors via return codes (never crash).
 */

#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <android/log.h>

#define TAG "OpenCodeTerm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    int master;
    pid_t child;
    int reaped;
    int exit_code;
} Session;

static Session *get_session(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env; (void)thiz;
    if (handle == 0) return NULL;
    Session *s = (Session *)(intptr_t)handle;
    if (s->master < 0 && s->child <= 0) return NULL; // already closed
    return s;
}

static jint throw_or_code(JNIEnv *env, jint code, const char *msg) {
    if (code < 0 && msg) {
        LOGE("%s: %s (errno=%d)", msg, strerror(-code), -code);
    }
    (void)env;
    return code;
}

/* ------------------------------------------------------------------ */
/* Session creation                                                    */
/* ------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeCreateSession(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    Session *s = (Session *)calloc(1, sizeof(Session));
    if (!s) return 0;
    s->master = -1;
    s->child = -1;
    s->reaped = 0;
    s->exit_code = -1;
    return (jlong)(intptr_t)s;
}

static int set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : 80);
    return ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeStart(
        JNIEnv *env, jclass clazz, jlong handle, jstring jshell, jstring jcwd,
        jobjectArray jcmd, jobjectArray jenv) {
    (void)clazz;
    Session *s = get_session(env, NULL, handle);
    if (!s) return -EINVAL;

    const char *shell = jshell ? (*env)->GetStringUTFChars(env, jshell, NULL) : "/system/bin/sh";
    const char *cwd = jcwd ? (*env)->GetStringUTFChars(env, jcwd, NULL) : "/";
    if (!shell || !cwd) {
        if (shell) (*env)->ReleaseStringUTFChars(env, jshell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return -ENOMEM;
    }

    int cmd_len = jcmd ? (*env)->GetArrayLength(env, jcmd) : 0;
    int env_len = jenv ? (*env)->GetArrayLength(env, jenv) : 0;

    char **argv = (char **)calloc((size_t)cmd_len + 2, sizeof(char *));
    char **envp = (char **)calloc((size_t)env_len + 1, sizeof(char *));
    if (!argv || !envp) {
        free(argv); free(envp);
        (*env)->ReleaseStringUTFChars(env, jshell, shell);
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return -ENOMEM;
    }

    argv[0] = strdup(shell);
    for (int i = 0; i < cmd_len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jcmd, i);
        const char *c = js ? (*env)->GetStringUTFChars(env, js, NULL) : "";
        argv[i + 1] = strdup(c ? c : "");
        if (js) (*env)->ReleaseStringUTFChars(env, js, c);
    }
    for (int i = 0; i < env_len; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jenv, i);
        const char *c = js ? (*env)->GetStringUTFChars(env, js, NULL) : "";
        envp[i] = strdup(c ? c : "");
        if (js) (*env)->ReleaseStringUTFChars(env, js, c);
    }

    /* Allocate PTY with posix_openpt (not forkpty — more portable across NDKs). */
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) {
        int e = -errno;
        LOGE("posix_openpt failed: %s", strerror(errno));
        free(argv); free(envp);
        (*env)->ReleaseStringUTFChars(env, jshell, shell);
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return throw_or_code(env, e, "posix_openpt");
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        int e = -errno;
        LOGE("grantpt/unlockpt failed: %s", strerror(errno));
        close(master);
        free(argv); free(envp);
        (*env)->ReleaseStringUTFChars(env, jshell, shell);
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return throw_or_code(env, e, "grantpt");
    }

    char slave_path[64];
    memset(slave_path, 0, sizeof(slave_path));
    if (ptsname_r(master, slave_path, sizeof(slave_path) - 1) != 0) {
        int e = -errno;
        close(master);
        free(argv); free(envp);
        (*env)->ReleaseStringUTFChars(env, jshell, shell);
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return throw_or_code(env, e, "ptsname_r");
    }

    set_winsize(master, 24, 80);

    pid_t pid = fork();
    if (pid < 0) {
        int e = -errno;
        close(master);
        free(argv); free(envp);
        (*env)->ReleaseStringUTFChars(env, jshell, shell);
        (*env)->ReleaseStringUTFChars(env, jcwd, cwd);
        return throw_or_code(env, e, "fork");
    }

    if (pid == 0) {
        /* Child: become session leader, attach controlling terminal, exec. */
        close(master);
        setsid();
        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(127);
#ifdef TIOCSCTTY
        ioctl(slave, TIOCSCTTY, 0);
#endif
        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2) close(slave);
        if (chdir(cwd) != 0) _exit(127);
        environ = envp; /* replace environment wholesale */
        execv(shell, argv);
        _exit(127); /* exec failed */
    }

    /* Parent */
    free(argv);
    free(envp);
    (*env)->ReleaseStringUTFChars(env, jshell, shell);
    (*env)->ReleaseStringUTFChars(env, jcwd, cwd);

    s->master = master;
    s->child = pid;
    s->reaped = 0;
    s->exit_code = -1;
    return 0;
}

/* ------------------------------------------------------------------ */
/* I/O                                                                 */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeRead(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf) {
    (void)clazz;
    Session *s = get_session(env, NULL, handle);
    if (!s || s->master < 0) return -EBADF;

    jsize len = (*env)->GetArrayLength(env, jbuf);
    if (len <= 0) return -EINVAL;
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (!buf) return -ENOMEM;

    for (;;) {
        ssize_t n = read(s->master, buf, (size_t)len);
        if (n > 0) {
            (*env)->ReleaseByteArrayElements(env, jbuf, buf, 0);
            return (jint)n;
        }
        if (n == 0) {
            (*env)->ReleaseByteArrayElements(env, jbuf, buf, 0);
            return 0; /* EOF */
        }
        if (errno == EINTR) continue;
        int e = -errno;
        (*env)->ReleaseByteArrayElements(env, jbuf, buf, 0);
        if (e == -EIO) return 0; /* slave closed — treat as EOF */
        return throw_or_code(env, e, "read");
    }
}

JNIEXPORT jint JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeWrite(
        JNIEnv *env, jclass clazz, jlong handle, jbyteArray jbuf, jint len) {
    (void)clazz;
    Session *s = get_session(env, NULL, handle);
    if (!s || s->master < 0) return -EBADF;
    if (len <= 0) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    if (!buf) return -ENOMEM;

    jint total = 0;
    while (total < len) {
        ssize_t n = write(s->master, buf + total, (size_t)(len - total));
        if (n > 0) {
            total += (jint)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        int e = (n < 0) ? -errno : -EIO;
        (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
        return throw_or_code(env, e, "write");
    }
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
    return total;
}

JNIEXPORT jint JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeResize(
        JNIEnv *env, jclass clazz, jlong handle, jint rows, jint cols) {
    (void)env; (void)clazz;
    Session *s = get_session(NULL, NULL, handle);
    if (!s || s->master < 0) return -EBADF;
    if (set_winsize(s->master, rows, cols) < 0) return -errno;
    if (s->child > 0) kill(-s->child, SIGWINCH);
    return 0;
}

JNIEXPORT void JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeSendSignal(
        JNIEnv *env, jclass clazz, jlong handle, jint sig) {
    (void)env; (void)clazz;
    Session *s = get_session(NULL, NULL, handle);
    if (!s || s->child <= 0) return;
    /* Signal the whole foreground process group of the child. */
    if (kill(-s->child, sig) < 0) kill(s->child, sig);
}

/* ------------------------------------------------------------------ */
/* Wait / close                                                        */
/* ------------------------------------------------------------------ */

JNIEXPORT jint JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeWaitFor(
        JNIEnv *env, jclass clazz, jlong handle, jint timeout_ms) {
    (void)env; (void)clazz;
    Session *s = get_session(NULL, NULL, handle);
    if (!s || s->child <= 0) return -EBADF;
    if (s->reaped) return s->exit_code;

    int elapsed = 0;
    int status = 0;
    for (;;) {
        pid_t r = waitpid(s->child, &status, WNOHANG);
        if (r == s->child) {
            s->reaped = 1;
            s->exit_code = WIFEXITED(status) ? WEXITSTATUS(status)
                          : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
            return s->exit_code;
        }
        if (r < 0) return (errno == ECHILD) ? 0 : -errno;
        if (timeout_ms >= 0 && elapsed >= timeout_ms) return -2; /* timeout */
        usleep(20000);
        elapsed += 20;
    }
}

JNIEXPORT void JNICALL
Java_ai_opencode_term_native_NativeTerminal_nativeCloseSession(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    Session *s = get_session(NULL, NULL, handle);
    if (!s) return;

    if (s->child > 0 && !s->reaped) {
        kill(-s->child, SIGHUP);
        /* Give it a moment, then force. */
        int status = 0;
        for (int i = 0; i < 10; i++) {
            if (waitpid(s->child, &status, WNOHANG) == s->child) { s->reaped = 1; break; }
            usleep(20000);
        }
        if (!s->reaped) {
            kill(-s->child, SIGKILL);
            waitpid(s->child, &status, 0);
            s->reaped = 1;
            s->exit_code = WIFEXITED(status) ? WEXITSTATUS(status)
                          : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
        }
    }
    if (s->master >= 0) {
        close(s->master);
        s->master = -1;
    }
    s->child = -1;
    free(s);
}
