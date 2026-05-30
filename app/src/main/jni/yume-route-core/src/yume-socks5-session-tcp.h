/*
 ============================================================================
 Name        : yume-socks5-session-tcp.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session TCP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_SESSION_TCP_H__
#define __YUME_SOCKS5_SESSION_TCP_H__

#include <pthread.h>

#include <yume-ring-buffer.h>
#include <yume-socks5-client-tcp.h>

#include "yume-socks5-session.h"

#define YUME_SOCKS5_SESSION_TCP(p) ((YumeSocks5SessionTCP *)p)
#define YUME_SOCKS5_SESSION_TCP_CLASS(p) ((YumeSocks5SessionTCPClass *)p)
#define YUME_SOCKS5_SESSION_TCP_TYPE (yume_socks5_session_tcp_class ())

typedef struct _YumeSocks5SessionTCP YumeSocks5SessionTCP;
typedef struct _YumeSocks5SessionTCPClass YumeSocks5SessionTCPClass;

struct _YumeSocks5SessionTCP
{
    YumeSocks5ClientTCP base;

    YumeSocks5SessionData data;

    struct pbuf *queue;
    struct tcp_pcb *pcb;
    YumeTaskMutex *mutex;
    YumeRingBuffer *buffer;
    pthread_mutex_t direct_mutex;
    pthread_cond_t direct_cond;
    pthread_t direct_relay_thread;
    int direct_relay_started;
    int direct_peer_fd;
    int direct_stream_handle;
    int direct_open_done;
    int direct_open_status;
    int direct_timeout_ms;
    volatile int direct_stop;
    volatile int direct_download_done;
    int pcb_eof;
    char direct_host[256];
    int direct_port;
};

struct _YumeSocks5SessionTCPClass
{
    YumeSocks5ClientTCPClass base;

    YumeSocks5SessionIface session;
};

YumeObjectClass *yume_socks5_session_tcp_class (void);

int yume_socks5_session_tcp_construct (YumeSocks5SessionTCP *self,
                                      struct tcp_pcb *pcb, YumeTaskMutex *mutex);

YumeSocks5SessionTCP *yume_socks5_session_tcp_new (struct tcp_pcb *pcb,
                                                 YumeTaskMutex *mutex);

#endif /* __YUME_SOCKS5_SESSION_TCP_H__ */
