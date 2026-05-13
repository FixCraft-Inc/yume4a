/*
 ============================================================================
 Name        : yume-socks5-client.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2024 hev
 Description : Socks5 Client
 ============================================================================
 */

#ifndef __YUME_SOCKS5_CLIENT_H__
#define __YUME_SOCKS5_CLIENT_H__

#include "yume-socks5.h"
#include "yume-socks5-proto.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_CLIENT(p) ((YumeSocks5Client *)p)
#define YUME_SOCKS5_CLIENT_CLASS(p) ((YumeSocks5ClientClass *)p)
#define YUME_SOCKS5_CLIENT_TYPE (yume_socks5_client_class ())

typedef struct _YumeSocks5Client YumeSocks5Client;
typedef struct _YumeSocks5ClientClass YumeSocks5ClientClass;

struct _YumeSocks5Client
{
    YumeSocks5 base;

    struct
    {
        const char *user;
        const char *pass;
    } auth;
};

struct _YumeSocks5ClientClass
{
    YumeSocks5Class base;

    YumeSocks5Addr *(*get_upstream_addr) (YumeSocks5Client *self);
    int (*set_upstream_addr) (YumeSocks5Client *self, YumeSocks5Addr *addr);
};

YumeObjectClass *yume_socks5_client_class (void);

int yume_socks5_client_construct (YumeSocks5Client *self, YumeSocks5Type type);

int yume_socks5_client_connect (YumeSocks5Client *self, const char *addr,
                               int port);

int yume_socks5_client_handshake (YumeSocks5Client *self, int pipeline);

void yume_socks5_client_set_auth (YumeSocks5Client *self, const char *user,
                                 const char *pass);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_CLIENT_H__ */
