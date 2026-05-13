/*
 ============================================================================
 Name        : yume-socks5-udp.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 UDP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_UDP_H__
#define __YUME_SOCKS5_UDP_H__

#include "yume-socks5-proto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_UDP(p) ((YumeSocks5UDP *)p)
#define YUME_SOCKS5_UDP_IFACE(p) ((YumeSocks5UDPIface *)p)
#define YUME_SOCKS5_UDP_TYPE (yume_socks5_udp_iface ())

typedef void YumeSocks5UDP;
typedef struct _YumeSocks5UDPMsg YumeSocks5UDPMsg;
typedef struct _YumeSocks5UDPIface YumeSocks5UDPIface;

struct _YumeSocks5UDPMsg
{
    YumeSocks5Addr *addr;
    void *buf;
    size_t len;
};

struct _YumeSocks5UDPIface
{
    int (*get_fd) (YumeSocks5UDP *self);
    int (*splicer) (YumeSocks5UDP *self, int fd);
};

void *yume_socks5_udp_iface (void);

int yume_socks5_udp_get_fd (YumeSocks5UDP *self);

int yume_socks5_udp_sendmmsg (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num);
int yume_socks5_udp_recvmmsg (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num, int nonblock);

int yume_socks5_udp_splice (YumeSocks5UDP *self, int fd);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_UDP_H__ */
