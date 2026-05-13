/*
 ============================================================================
 Name        : yume-socks5-client-tcp.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Client TCP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_CLIENT_TCP_H__
#define __YUME_SOCKS5_CLIENT_TCP_H__

#include "yume-socks5-tcp.h"
#include "yume-socks5-proto.h"

#include "yume-socks5-client.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_CLIENT_TCP(p) ((YumeSocks5ClientTCP *)p)
#define YUME_SOCKS5_CLIENT_TCP_CLASS(p) ((YumeSocks5ClientTCPClass *)p)
#define YUME_SOCKS5_CLIENT_TCP_TYPE (yume_socks5_client_tcp_class ())

typedef struct _YumeSocks5ClientTCP YumeSocks5ClientTCP;
typedef struct _YumeSocks5ClientTCPClass YumeSocks5ClientTCPClass;

struct _YumeSocks5ClientTCP
{
    YumeSocks5Client base;

    YumeSocks5Addr *addr;
};

struct _YumeSocks5ClientTCPClass
{
    YumeSocks5ClientClass base;

    YumeSocks5TCPIface tcp;
};

YumeObjectClass *yume_socks5_client_tcp_class (void);

int yume_socks5_client_tcp_construct (YumeSocks5ClientTCP *self,
                                     const YumeSocks5Addr *addr);

YumeSocks5ClientTCP *yume_socks5_client_tcp_new_name (const char *name, int port);
YumeSocks5ClientTCP *yume_socks5_client_tcp_new_ipv4 (const void *ipv4, int port);
YumeSocks5ClientTCP *yume_socks5_client_tcp_new_ipv6 (const void *ipv6, int port);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_CLIENT_TCP_H__ */
