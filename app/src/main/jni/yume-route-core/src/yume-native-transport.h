#ifndef __YUME_NATIVE_TRANSPORT_H__
#define __YUME_NATIVE_TRANSPORT_H__

#include <jni.h>
#include <sys/types.h>

int yume_native_transport_set (JNIEnv *env, jobject transport);
void yume_native_transport_clear (JNIEnv *env);

int yume_native_transport_has (void);
void yume_native_transport_set_direct_enabled (int enabled);
int yume_native_transport_is_direct_enabled (void);

int yume_native_transport_is_alive (void);
int yume_native_transport_open (const char *host, int port, const char *proto,
                             int timeout_ms);
ssize_t yume_native_transport_send_direct (int handle, const void *data, size_t len);
ssize_t yume_native_transport_send (int handle, const void *data, size_t len);
ssize_t yume_native_transport_read_into (int handle, void *data, size_t len,
                                      int timeout_ms);
ssize_t yume_native_transport_read (int handle, unsigned char **data,
                                 int timeout_ms);
int yume_native_transport_close_stream (int handle, const char *reason);
int yume_native_transport_close (void);

#endif /* __YUME_NATIVE_TRANSPORT_H__ */
