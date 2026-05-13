/*
 ============================================================================
 Name        : yume-socks5-client-udp.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2023 hev
 Description : Socks5 Client UDP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_CLIENT_UDP_H__
#define __YUME_SOCKS5_CLIENT_UDP_H__

#include "yume-socks5-udp.h"

#include "yume-socks5-client.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_CLIENT_UDP(p) ((YumeSocks5ClientUDP *)p)
#define YUME_SOCKS5_CLIENT_UDP_CLASS(p) ((YumeSocks5ClientUDPClass *)p)
#define YUME_SOCKS5_CLIENT_UDP_TYPE (yume_socks5_client_udp_class ())

typedef struct _YumeSocks5ClientUDP YumeSocks5ClientUDP;
typedef struct _YumeSocks5ClientUDPClass YumeSocks5ClientUDPClass;

struct _YumeSocks5ClientUDP
{
    YumeSocks5Client base;

    int fd;
};

struct _YumeSocks5ClientUDPClass
{
    YumeSocks5ClientClass base;

    YumeSocks5UDPIface udp;
};

YumeObjectClass *yume_socks5_client_udp_class (void);

int yume_socks5_client_udp_construct (YumeSocks5ClientUDP *self,
                                     YumeSocks5Type type);

YumeSocks5ClientUDP *yume_socks5_client_udp_new (YumeSocks5Type type);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_CLIENT_UDP_H__ */
