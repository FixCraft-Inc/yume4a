/*
 ============================================================================
 Name        : yume-bridge-jni.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Jave Native Interface
 ============================================================================
 */

#ifdef ANDROID

#include <jni.h>
#include <pthread.h>

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>

#include <android/log.h>

#include "yume-route-main.h"
#include "yume-mapped-dns.h"
#include "yume-native-transport.h"

#include "yume-bridge-jni.h"

#define YUME_JNI_TAG "yume-bridge-jni"
#define YUME_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, YUME_JNI_TAG, __VA_ARGS__)
#define YUME_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  YUME_JNI_TAG, __VA_ARGS__)
#define YUME_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  YUME_JNI_TAG, __VA_ARGS__)

/* clang-format off */
#ifndef PKGNAME
#define PKGNAME com/fixcraft/yume/tunnel
#endif
#ifndef CLSNAME
#define CLSNAME YumeTunnelBridge
#endif
/* clang-format on */

#define STR(s) STR_ARG (s)
#define STR_ARG(c) #c
#define N_ELEMENTS(arr) (sizeof (arr) / sizeof ((arr)[0]))

typedef struct _ThreadData ThreadData;
typedef struct _JoinThreadData JoinThreadData;

struct _ThreadData
{
    char *path;
    int fd;
};

struct _JoinThreadData
{
    pthread_t worker;
};

static int is_working;
static int join_in_progress;
static int stop_requested;
static int thread_joinable;
static int active_fd = -1;
static JavaVM *java_vm;
static pthread_t work_thread;
static pthread_mutex_t mutex;
static pthread_key_t current_jni_env;

static void native_start_service (JNIEnv *env, jobject thiz, jstring conig_path,
                                  jint fd);
static void native_stop_service (JNIEnv *env, jobject thiz);
static jlongArray native_get_stats (JNIEnv *env, jobject thiz);
static jstring native_lookup_mapped_host (JNIEnv *env, jobject thiz,
                                          jstring ipv4_address);
static void native_set_native_transport (JNIEnv *env, jobject thiz,
                                         jobject transport);
static void native_clear_native_transport (JNIEnv *env, jobject thiz);
static void native_set_native_direct_enabled (JNIEnv *env, jobject thiz,
                                              jboolean enabled);
static jboolean native_has_native_transport (JNIEnv *env, jobject thiz);
static jboolean native_is_tunnel_running (JNIEnv *env, jobject thiz);
static void clear_joined_thread_state (pthread_t thread);
static void *join_thread_handler (void *data);

static JNINativeMethod native_methods[] = {
    { "startTunnel", "(Ljava/lang/String;I)V",
      (void *)native_start_service },
    { "stopTunnel", "()V", (void *)native_stop_service },
    { "getStats", "()[J", (void *)native_get_stats },
    { "lookupMappedHost", "(Ljava/lang/String;)Ljava/lang/String;",
      (void *)native_lookup_mapped_host },
    { "attachNativeTransport",
      "(Lcom/fixcraft/yume/transport/YumeNativeTransport;)V",
      (void *)native_set_native_transport },
    { "detachNativeTransport", "()V",
      (void *)native_clear_native_transport },
    { "setNativeDirectEnabled", "(Z)V",
      (void *)native_set_native_direct_enabled },
    { "hasNativeTransport", "()Z",
      (void *)native_has_native_transport },
    { "isTunnelRunning", "()Z",
      (void *)native_is_tunnel_running },
};

static void
detach_current_thread (void *env)
{
    (*java_vm)->DetachCurrentThread (java_vm);
}

JNIEnv *
yume_jni_get_env (void)
{
    JNIEnv *env = pthread_getspecific (current_jni_env);

    if (env)
        return env;

    if ((*java_vm)->AttachCurrentThread (java_vm, &env, NULL) != JNI_OK)
        return NULL;

    pthread_setspecific (current_jni_env, env);
    return env;
}

jint
JNI_OnLoad (JavaVM *vm, void *reserved)
{
    JNIEnv *env = NULL;
    jclass klass;
    const char *bridge_class = STR (PKGNAME) "/" STR (CLSNAME);

    java_vm = vm;
    if (JNI_OK != (*vm)->GetEnv (vm, (void **)&env, JNI_VERSION_1_4)) {
        YUME_LOGE ("JNI_OnLoad: GetEnv() failed; aborting load");
        return 0;
    }

    klass = (*env)->FindClass (env, bridge_class);
    if (!klass) {
        if ((*env)->ExceptionCheck (env)) {
            (*env)->ExceptionDescribe (env);
            (*env)->ExceptionClear (env);
        }
        YUME_LOGE ("JNI_OnLoad: FindClass(\"%s\") returned NULL; "
                   "the Kotlin bridge class is missing or its package was renamed. "
                   "Make sure %s exists at runtime.",
                   bridge_class, bridge_class);
        return JNI_VERSION_1_4;
    }

    jint reg_rc = (*env)->RegisterNatives (env, klass, native_methods,
                                           N_ELEMENTS (native_methods));
    if (reg_rc < 0) {
        if ((*env)->ExceptionCheck (env)) {
            (*env)->ExceptionDescribe (env);
            (*env)->ExceptionClear (env);
        }
        YUME_LOGE ("JNI_OnLoad: RegisterNatives() failed with rc=%d on %s; "
                   "the next external fun call from Kotlin will throw "
                   "UnsatisfiedLinkError. Most common cause: the native_methods "
                   "table got out of sync with the Kotlin external fun signatures. "
                   "Cross-check yume-bridge-jni.c native_methods[] against "
                   "YumeTunnelBridge.kt.",
                   reg_rc, bridge_class);
    } else {
        YUME_LOGI ("JNI_OnLoad: registered %zu native methods on %s",
                   N_ELEMENTS (native_methods), bridge_class);
    }
    (*env)->DeleteLocalRef (env, klass);

    pthread_key_create (&current_jni_env, detach_current_thread);
    pthread_mutex_init (&mutex, NULL);

    return JNI_VERSION_1_4;
}

static void *
thread_handler (void *data)
{
    ThreadData *tdata = data;

    YUME_LOGI ("thread_handler: starting route worker, config=%s fd=%d",
               tdata->path ? tdata->path : "(null)", tdata->fd);
    yume_route_main (tdata->path, tdata->fd);
    YUME_LOGW ("thread_handler: route worker exited (yume_route_main returned). "
               "If this happens immediately after startTunnel, the YAML config "
               "or the TUN fd was rejected; check earlier yume-route-* logcat "
               "lines.");

    free (tdata->path);

    pthread_mutex_lock (&mutex);
    if (active_fd == tdata->fd) {
        close (active_fd);
        active_fd = -1;
    }
    is_working = 0;
    pthread_mutex_unlock (&mutex);

    free (tdata);
    return NULL;
}

static void
clear_joined_thread_state (pthread_t thread)
{
    pthread_mutex_lock (&mutex);
    if (thread_joinable && pthread_equal (work_thread, thread)) {
        thread_joinable = 0;
        is_working = 0;
        stop_requested = 0;
        active_fd = -1;
        memset (&work_thread, 0, sizeof (work_thread));
    }
    join_in_progress = 0;
    pthread_mutex_unlock (&mutex);
}

static void *
join_thread_handler (void *data)
{
    JoinThreadData *jdata = data;
    pthread_t thread = jdata->worker;

    free (jdata);
    pthread_join (thread, NULL);
    clear_joined_thread_state (thread);

    return NULL;
}

static void
native_start_service (JNIEnv *env, jobject thiz, jstring config_path, jint fd)
{
    const jbyte *bytes;
    ThreadData *tdata;
    pthread_t finished_thread;
    int wait_attempts = 0;
    int res;

restart:
    pthread_mutex_lock (&mutex);

    if (thread_joinable && !is_working && !join_in_progress) {
        finished_thread = work_thread;
        thread_joinable = 0;
        stop_requested = 0;
        memset (&work_thread, 0, sizeof (work_thread));
        pthread_mutex_unlock (&mutex);
        pthread_join (finished_thread, NULL);
        goto restart;
    }

    if (thread_joinable || is_working || join_in_progress || stop_requested) {
        pthread_mutex_unlock (&mutex);
        if (++wait_attempts <= 100) {
            usleep (20 * 1000);
            goto restart;
        }
        /* Do NOT close(fd) here. The Kotlin side called dup() then detachFd()
         * before passing the fd in, and its catch block on this throw path
         * adopts and closes it via ParcelFileDescriptor.adoptFd(...).close().
         * If we close first, fdsan detects the second close as a double-close
         * and SIGABRTs the process. That crash leaves the VPN service in a
         * half-running state where the TUN exists but no worker reads it,
         * which manifests to the user as "first request works, then dead." */
        YUME_LOGE ("native_start_service: previous route worker did not stop in time; "
                   "leaving fd=%d for Kotlin to clean up via adoptFd().close()",
                   fd);
        jclass ex = (*env)->FindClass (env, "java/lang/IllegalStateException");
        if (ex) {
            (*env)->ThrowNew (env, ex,
                              "previous Android tunnel backend is still stopping");
            (*env)->DeleteLocalRef (env, ex);
        }
        return;
    }

    tdata = malloc (sizeof (ThreadData));
    tdata->fd = fd;

    bytes = (const jbyte *)(*env)->GetStringUTFChars (env, config_path, NULL);
    tdata->path = strdup ((const char *)bytes);
    (*env)->ReleaseStringUTFChars (env, config_path, (const char *)bytes);

    active_fd = fd;
    res = pthread_create (&work_thread, NULL, thread_handler, tdata);
    if (res != 0) {
        YUME_LOGE ("native_start_service: pthread_create failed rc=%d (%s)",
                   res, strerror (res));
        if (fd >= 0)
            close (fd);
        active_fd = -1;
        free (tdata->path);
        free (tdata);
        goto exit;
    }

    is_working = 1;
    stop_requested = 0;
    thread_joinable = 1;
    YUME_LOGI ("native_start_service: route worker thread started for fd=%d", fd);
exit:
    pthread_mutex_unlock (&mutex);
}

static void
native_stop_service (JNIEnv *env, jobject thiz)
{
    JoinThreadData *jdata = NULL;
    pthread_t thread;
    pthread_t join_thread;
    int fd_to_close = -1;
    int should_join = 0;
    int should_quit = 0;

    pthread_mutex_lock (&mutex);

    if (!thread_joinable || join_in_progress)
        goto exit;

    thread = work_thread;
    should_join = 1;
    should_quit = is_working;
    stop_requested = 1;
    join_in_progress = 1;
exit:
    pthread_mutex_unlock (&mutex);

    if (!should_join)
        return;

    if (should_quit)
        yume_route_stop ();

    pthread_mutex_lock (&mutex);
    if (active_fd >= 0) {
        fd_to_close = active_fd;
        active_fd = -1;
    }
    pthread_mutex_unlock (&mutex);

    if (fd_to_close >= 0) {
        close (fd_to_close);
        YUME_LOGI ("native_stop_service: closed duplicated TUN fd=%d", fd_to_close);
    }

    jdata = malloc (sizeof (JoinThreadData));
    if (jdata) {
        jdata->worker = thread;
        if (pthread_create (&join_thread, NULL, join_thread_handler, jdata) == 0) {
            pthread_detach (join_thread);
            return;
        }
        free (jdata);
    }

    pthread_join (thread, NULL);
    clear_joined_thread_state (thread);
}

static jlongArray
native_get_stats (JNIEnv *env, jobject thiz)
{
    size_t tx_packets, rx_packets, tx_bytes, rx_bytes;
    jlongArray res;
    jlong array[4];

    yume_route_stats (&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    array[0] = tx_packets;
    array[1] = tx_bytes;
    array[2] = rx_packets;
    array[3] = rx_bytes;

    res = (*env)->NewLongArray (env, 4);
    (*env)->SetLongArrayRegion (env, res, 0, 4, array);

    return res;
}

static jstring
native_lookup_mapped_host (JNIEnv *env, jobject thiz, jstring ipv4_address)
{
    const char *bytes;
    struct in_addr addr;
    YumeMappedDns *dns;
    const char *mapped_name;
    jstring result = NULL;

    dns = yume_mapped_dns_get ();
    if (!dns || !ipv4_address)
        return NULL;

    bytes = (*env)->GetStringUTFChars (env, ipv4_address, NULL);
    if (!bytes)
        return NULL;

    if (inet_pton (AF_INET, bytes, &addr) == 1) {
        mapped_name = yume_mapped_dns_lookup (dns, ntohl (addr.s_addr));
        if (mapped_name)
            result = (*env)->NewStringUTF (env, mapped_name);
    }

    (*env)->ReleaseStringUTFChars (env, ipv4_address, bytes);

    return result;
}

static void
native_set_native_transport (JNIEnv *env, jobject thiz, jobject transport)
{
    int rc;

    if (!transport) {
        yume_native_transport_clear (env);
        return;
    }

    rc = yume_native_transport_set (env, transport);
    if (rc < 0) {
        YUME_LOGE ("attachNativeTransport: yume_native_transport_set rc=%d "
                   "(method-id resolution probably failed; the next "
                   "hasNativeTransport() will return false and Kotlin will "
                   "treat the technique as unavailable)",
                   rc);
        yume_native_transport_clear (env);
    } else {
        YUME_LOGI ("attachNativeTransport: native transport registered");
    }
}

static void
native_clear_native_transport (JNIEnv *env, jobject thiz)
{
    yume_native_transport_clear (env);
}

static void
native_set_native_direct_enabled (JNIEnv *env, jobject thiz, jboolean enabled)
{
    yume_native_transport_set_direct_enabled (enabled ? 1 : 0);
}

static jboolean
native_has_native_transport (JNIEnv *env, jobject thiz)
{
    return yume_native_transport_has () ? JNI_TRUE : JNI_FALSE;
}

static jboolean
native_is_tunnel_running (JNIEnv *env, jobject thiz)
{
    jboolean running;

    pthread_mutex_lock (&mutex);
    running = (is_working && !stop_requested) ? JNI_TRUE : JNI_FALSE;
    pthread_mutex_unlock (&mutex);

    return running;
}

#endif /* ANDROID */
