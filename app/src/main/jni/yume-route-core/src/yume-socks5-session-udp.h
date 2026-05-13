/*
 ============================================================================
 Name        : yume-socks5-session-udp.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session UDP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_SESSION_UDP_H__
#define __YUME_SOCKS5_SESSION_UDP_H__

#include <pthread.h>

#include <yume-socks5-client-udp.h>

#include "yume-socks5-session.h"

#define YUME_SOCKS5_SESSION_UDP(p) ((YumeSocks5SessionUDP *)p)
#define YUME_SOCKS5_SESSION_UDP_CLASS(p) ((YumeSocks5SessionUDPClass *)p)
#define YUME_SOCKS5_SESSION_UDP_TYPE (yume_socks5_session_udp_class ())

typedef struct _YumeSocks5SessionUDP YumeSocks5SessionUDP;
typedef struct _YumeSocks5SessionUDPClass YumeSocks5SessionUDPClass;

struct _YumeSocks5SessionUDP
{
    YumeSocks5ClientUDP base;

    YumeSocks5SessionData data;

    YumeList frame_list;
    struct udp_pcb *pcb;
    YumeTaskMutex *mutex;
    YumeSocks5Addr *direct_addr;
    int frames;
    int addr;
    int port;
    pthread_t direct_relay_thread;
    int direct_relay_started;
    int direct_peer_fd;
    int direct_stream_handle;
    int direct_addr_len;
    int direct_timeout_ms;
    volatile int direct_stop;
    char direct_host[256];
    int direct_port;
};

struct _YumeSocks5SessionUDPClass
{
    YumeSocks5ClientUDPClass base;

    YumeSocks5SessionIface session;
};

YumeObjectClass *yume_socks5_session_udp_class (void);

int yume_socks5_session_udp_construct (YumeSocks5SessionUDP *self,
                                      struct udp_pcb *pcb, YumeTaskMutex *mutex);

YumeSocks5SessionUDP *yume_socks5_session_udp_new (struct udp_pcb *pcb,
                                                 YumeTaskMutex *mutex);

#endif /* __YUME_SOCKS5_SESSION_UDP_H__ */
