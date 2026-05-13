/*
 ============================================================================
 Name        : yume-socks5.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2023 hev
 Description : Socks5
 ============================================================================
 */

#ifndef __YUME_SOCKS5_H__
#define __YUME_SOCKS5_H__

#include <netinet/in.h>
#include <sys/socket.h>

#include <yume-object.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5(p) ((YumeSocks5 *)p)
#define YUME_SOCKS5_CLASS(p) ((YumeSocks5Class *)p)
#define YUME_SOCKS5_TYPE (yume_socks5_class ())

typedef struct _YumeSocks5 YumeSocks5;
typedef struct _YumeSocks5Class YumeSocks5Class;
typedef enum _YumeSocks5Type YumeSocks5Type;
typedef enum _YumeSocks5AddrFamily YumeSocks5AddrFamily;

enum _YumeSocks5Type
{
    YUME_SOCKS5_TYPE_NONE,
    YUME_SOCKS5_TYPE_TCP,
    YUME_SOCKS5_TYPE_UDP_IN_TCP,
    YUME_SOCKS5_TYPE_UDP_IN_UDP,
};

enum _YumeSocks5AddrFamily
{
    YUME_SOCKS5_ADDR_FAMILY_IPV4 = AF_INET,
    YUME_SOCKS5_ADDR_FAMILY_IPV6 = AF_INET6,
    YUME_SOCKS5_ADDR_FAMILY_UNSPEC = AF_UNSPEC,
};

struct _YumeSocks5
{
    YumeObject base;

    int fd;
    int timeout;
    int udp_associated;
    YumeSocks5Type type;
    YumeSocks5AddrFamily addr_family;
};

struct _YumeSocks5Class
{
    YumeObjectClass base;

    int (*binder) (YumeSocks5 *self, int sock, const struct sockaddr *dest);
};

YumeObjectClass *yume_socks5_class (void);

int yume_socks5_construct (YumeSocks5 *self, YumeSocks5Type type);

int yume_socks5_get_timeout (YumeSocks5 *self);
void yume_socks5_set_timeout (YumeSocks5 *self, int timeout);

YumeSocks5AddrFamily yume_socks5_get_addr_family (YumeSocks5 *self);
void yume_socks5_set_addr_family (YumeSocks5 *self, YumeSocks5AddrFamily family);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_H__ */
