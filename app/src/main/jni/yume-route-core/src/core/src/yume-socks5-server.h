/*
 ============================================================================
 Name        : yume-socks5-server.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2023 hev
 Description : Socks5 Server
 ============================================================================
 */

#ifndef __YUME_SOCKS5_SERVER_H__
#define __YUME_SOCKS5_SERVER_H__

#include <yume-object.h>

#include "yume-socks5.h"
#include "yume-socks5-tcp.h"
#include "yume-socks5-udp.h"
#include "yume-socks5-user.h"
#include "yume-socks5-authenticator.h"

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_SERVER(p) ((YumeSocks5Server *)p)
#define YUME_SOCKS5_SERVER_CLASS(p) ((YumeSocks5ServerClass *)p)
#define YUME_SOCKS5_SERVER_TYPE (yume_socks5_server_class ())

typedef struct _YumeSocks5Server YumeSocks5Server;
typedef struct _YumeSocks5ServerClass YumeSocks5ServerClass;

struct _YumeSocks5Server
{
    YumeSocks5 base;

    int fds[2];

    union
    {
        YumeObject *obj;
        YumeSocks5User *user;
        YumeSocks5Authenticator *auth;
    };
};

struct _YumeSocks5ServerClass
{
    YumeSocks5Class base;

    int (*binder) (YumeSocks5Server *self, int sock, struct sockaddr_in6 *src);

    YumeSocks5TCPIface tcp;
    YumeSocks5UDPIface udp;
};

YumeObjectClass *yume_socks5_server_class (void);

int yume_socks5_server_construct (YumeSocks5Server *self, int fd);

YumeSocks5Server *yume_socks5_server_new (int fd);

void yume_socks5_server_set_auth (YumeSocks5Server *self,
                                 YumeSocks5Authenticator *auth);

int yume_socks5_server_run (YumeSocks5Server *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_SERVER_H__ */
