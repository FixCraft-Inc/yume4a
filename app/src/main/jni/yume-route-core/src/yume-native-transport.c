#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "yume-bridge-jni.h"
#include "yume-native-transport.h"

static pthread_mutex_t transport_mutex = PTHREAD_MUTEX_INITIALIZER;
static jobject transport_ref;
static jmethodID open_stream_method;
static jmethodID read_stream_method;
static jmethodID read_into_method;
static jmethodID send_data_method;
static jmethodID send_direct_method;
static jmethodID close_stream_method;
static jmethodID is_alive_method;
static jmethodID close_method;
static int direct_enabled;

static jobject
transport_local_ref (JNIEnv *env, jmethodID *open_method, jmethodID *read_method,
                     jmethodID *read_into, jmethodID *send_method,
                     jmethodID *send_direct, jmethodID *close_stream,
                     jmethodID *alive_method, jmethodID *shutdown_method)
{
    jobject local = NULL;

    pthread_mutex_lock (&transport_mutex);
    if (transport_ref) {
        local = (*env)->NewLocalRef (env, transport_ref);
        if (open_method)
            *open_method = open_stream_method;
        if (read_method)
            *read_method = read_stream_method;
        if (read_into)
            *read_into = read_into_method;
        if (send_method)
            *send_method = send_data_method;
        if (send_direct)
            *send_direct = send_direct_method;
        if (close_stream)
            *close_stream = close_stream_method;
        if (alive_method)
            *alive_method = is_alive_method;
        if (shutdown_method)
            *shutdown_method = close_method;
    }
    pthread_mutex_unlock (&transport_mutex);

    return local;
}

static int
transport_clear_exception (JNIEnv *env)
{
    if (!(*env)->ExceptionCheck (env))
        return 0;

    (*env)->ExceptionClear (env);
    return -1;
}

int
yume_native_transport_set (JNIEnv *env, jobject transport)
{
    jobject global = NULL;
    jclass klass;

    if (!env)
        return -1;

    if (!transport) {
        yume_native_transport_clear (env);
        return 0;
    }

    global = (*env)->NewGlobalRef (env, transport);
    if (!global || transport_clear_exception (env) < 0)
        return -1;

    klass = (*env)->GetObjectClass (env, transport);
    if (!klass || transport_clear_exception (env) < 0) {
        (*env)->DeleteGlobalRef (env, global);
        return -1;
    }

    pthread_mutex_lock (&transport_mutex);

    if (transport_ref)
        (*env)->DeleteGlobalRef (env, transport_ref);

    transport_ref = global;
    open_stream_method = (*env)->GetMethodID (
        env, klass, "openStreamInternal", "(Ljava/lang/String;ILjava/lang/String;I)I");
    read_stream_method = (*env)->GetMethodID (env, klass, "readStreamInternal",
                                              "(II)[B");
    read_into_method = (*env)->GetMethodID (
        env, klass, "readIntoInternal", "(ILjava/nio/ByteBuffer;I)I");
    send_data_method = (*env)->GetMethodID (env, klass, "sendDataInternal",
                                            "(I[B)I");
    send_direct_method = (*env)->GetMethodID (
        env, klass, "sendDirectInternal", "(ILjava/nio/ByteBuffer;I)I");
    close_stream_method = (*env)->GetMethodID (
        env, klass, "closeStreamInternal", "(ILjava/lang/String;)I");
    is_alive_method = (*env)->GetMethodID (env, klass, "isAliveInternal", "()I");
    close_method = (*env)->GetMethodID (env, klass, "closeInternal", "()I");

    pthread_mutex_unlock (&transport_mutex);

    (*env)->DeleteLocalRef (env, klass);

    if (transport_clear_exception (env) < 0 || !open_stream_method ||
        !read_stream_method || !read_into_method || !send_data_method ||
        !send_direct_method || !close_stream_method || !is_alive_method ||
        !close_method) {
        yume_native_transport_clear (env);
        return -1;
    }

    return 0;
}

void
yume_native_transport_clear (JNIEnv *env)
{
    jobject old_ref = NULL;
    jmethodID shutdown_method = NULL;

    if (!env)
        env = yume_jni_get_env ();

    pthread_mutex_lock (&transport_mutex);
    old_ref = transport_ref;
    shutdown_method = close_method;
    transport_ref = NULL;
    open_stream_method = NULL;
    read_stream_method = NULL;
    read_into_method = NULL;
    send_data_method = NULL;
    send_direct_method = NULL;
    close_stream_method = NULL;
    is_alive_method = NULL;
    close_method = NULL;
    direct_enabled = 0;
    pthread_mutex_unlock (&transport_mutex);

    if (!env || !old_ref)
        return;

    if (shutdown_method)
        (*env)->CallIntMethod (env, old_ref, shutdown_method);
    transport_clear_exception (env);
    (*env)->DeleteGlobalRef (env, old_ref);
}

int
yume_native_transport_has (void)
{
    int has_transport;

    pthread_mutex_lock (&transport_mutex);
    has_transport = transport_ref != NULL;
    pthread_mutex_unlock (&transport_mutex);

    return has_transport;
}

void
yume_native_transport_set_direct_enabled (int enabled)
{
    pthread_mutex_lock (&transport_mutex);
    direct_enabled = !!enabled;
    pthread_mutex_unlock (&transport_mutex);
}

int
yume_native_transport_is_direct_enabled (void)
{
    int enabled;

    pthread_mutex_lock (&transport_mutex);
    enabled = direct_enabled && transport_ref != NULL;
    pthread_mutex_unlock (&transport_mutex);

    return enabled;
}

int
yume_native_transport_is_alive (void)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID alive_method = NULL;
    jint res;

    if (!env)
        return 0;

    transport = transport_local_ref (env, NULL, NULL, NULL, NULL, NULL, NULL,
                                     &alive_method, NULL);
    if (!transport || !alive_method)
        return 0;

    res = (*env)->CallIntMethod (env, transport, alive_method);
    if (transport_clear_exception (env) < 0)
        res = 0;

    (*env)->DeleteLocalRef (env, transport);
    return res ? 1 : 0;
}

int
yume_native_transport_open (const char *host, int port, const char *proto,
                         int timeout_ms)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jstring host_string = NULL;
    jstring proto_string = NULL;
    jint handle = -1;

    if (!env || !host || !proto)
        return -1;

    transport = transport_local_ref (env, &method, NULL, NULL, NULL, NULL, NULL,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    host_string = (*env)->NewStringUTF (env, host);
    proto_string = (*env)->NewStringUTF (env, proto);
    if (!host_string || !proto_string || transport_clear_exception (env) < 0)
        goto exit;

    handle = (*env)->CallIntMethod (env, transport, method, host_string, port,
                                    proto_string, timeout_ms);
    if (transport_clear_exception (env) < 0)
        handle = -1;

exit:
    if (host_string)
        (*env)->DeleteLocalRef (env, host_string);
    if (proto_string)
        (*env)->DeleteLocalRef (env, proto_string);
    (*env)->DeleteLocalRef (env, transport);

    return handle;
}

ssize_t
yume_native_transport_send (int handle, const void *data, size_t len)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jbyteArray array = NULL;
    jint res = -1;

    if (!env || handle <= 0 || !data || !len)
        return -1;

    transport = transport_local_ref (env, NULL, NULL, NULL, &method, NULL, NULL,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    array = (*env)->NewByteArray (env, len);
    if (!array || transport_clear_exception (env) < 0)
        goto exit;

    (*env)->SetByteArrayRegion (env, array, 0, len, data);
    if (transport_clear_exception (env) < 0)
        goto exit;

    res = (*env)->CallIntMethod (env, transport, method, handle, array);
    if (transport_clear_exception (env) < 0)
        res = -1;

exit:
    if (array)
        (*env)->DeleteLocalRef (env, array);
    (*env)->DeleteLocalRef (env, transport);

    return res;
}

ssize_t
yume_native_transport_send_direct (int handle, const void *data, size_t len)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jobject buffer = NULL;
    jint res = -1;

    if (!env || handle <= 0 || !data || !len)
        return -1;

    transport = transport_local_ref (env, NULL, NULL, NULL, NULL, &method, NULL,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    buffer = (*env)->NewDirectByteBuffer (env, (void *)data, len);
    if (!buffer || transport_clear_exception (env) < 0)
        goto exit;

    res = (*env)->CallIntMethod (env, transport, method, handle, buffer,
                                 (jint)len);
    if (transport_clear_exception (env) < 0)
        res = -1;

exit:
    if (buffer)
        (*env)->DeleteLocalRef (env, buffer);
    (*env)->DeleteLocalRef (env, transport);

    return res;
}

ssize_t
yume_native_transport_read (int handle, unsigned char **data, int timeout_ms)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jbyteArray array = NULL;
    jsize len;
    unsigned char *copy;

    if (data)
        *data = NULL;

    if (!env || !data || handle <= 0)
        return -1;

    transport = transport_local_ref (env, NULL, &method, NULL, NULL, NULL, NULL,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    array = (*env)->CallObjectMethod (env, transport, method, handle, timeout_ms);
    if (transport_clear_exception (env) < 0) {
        (*env)->DeleteLocalRef (env, transport);
        return -1;
    }

    if (!array) {
        (*env)->DeleteLocalRef (env, transport);
        return -1;
    }

    len = (*env)->GetArrayLength (env, array);
    if (len == 0) {
        (*env)->DeleteLocalRef (env, array);
        (*env)->DeleteLocalRef (env, transport);
        return 0;
    }

    copy = malloc (len);
    if (!copy) {
        (*env)->DeleteLocalRef (env, array);
        (*env)->DeleteLocalRef (env, transport);
        return -1;
    }

    (*env)->GetByteArrayRegion (env, array, 0, len, (jbyte *)copy);
    if (transport_clear_exception (env) < 0) {
        free (copy);
        copy = NULL;
        len = -1;
    }

    (*env)->DeleteLocalRef (env, array);
    (*env)->DeleteLocalRef (env, transport);

    *data = copy;
    return len;
}

ssize_t
yume_native_transport_read_into (int handle, void *data, size_t len, int timeout_ms)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jobject buffer = NULL;
    jint res = -1;

    if (!env || !data || !len || handle <= 0)
        return -1;

    transport = transport_local_ref (env, NULL, NULL, &method, NULL, NULL, NULL,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    buffer = (*env)->NewDirectByteBuffer (env, data, len);
    if (!buffer || transport_clear_exception (env) < 0)
        goto exit;

    res = (*env)->CallIntMethod (env, transport, method, handle, buffer,
                                 timeout_ms);
    if (transport_clear_exception (env) < 0)
        res = -1;

exit:
    if (buffer)
        (*env)->DeleteLocalRef (env, buffer);
    (*env)->DeleteLocalRef (env, transport);

    return res;
}

int
yume_native_transport_close_stream (int handle, const char *reason)
{
    JNIEnv *env = yume_jni_get_env ();
    jobject transport;
    jmethodID method = NULL;
    jstring reason_string = NULL;
    jint res = 0;

    if (!env || handle <= 0)
        return -1;

    transport = transport_local_ref (env, NULL, NULL, NULL, NULL, NULL, &method,
                                     NULL, NULL);
    if (!transport || !method)
        return -1;

    reason_string = (*env)->NewStringUTF (env, reason ? reason : "");
    if (!reason_string || transport_clear_exception (env) < 0)
        goto exit;

    res = (*env)->CallIntMethod (env, transport, method, handle, reason_string);
    if (transport_clear_exception (env) < 0)
        res = -1;

exit:
    if (reason_string)
        (*env)->DeleteLocalRef (env, reason_string);
    (*env)->DeleteLocalRef (env, transport);

    return res;
}

int
yume_native_transport_close (void)
{
    JNIEnv *env = yume_jni_get_env ();

    yume_native_transport_clear (env);
    return 0;
}
