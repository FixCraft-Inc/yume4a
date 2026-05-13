/*
 ============================================================================
 Name        : yume-socks5-tcp.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 hev
 Description : Socks5 TCP
 ============================================================================
 */

#ifndef __YUME_SOCKS5_TCP_H__
#define __YUME_SOCKS5_TCP_H__

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_SOCKS5_TCP(p) ((YumeSocks5TCP *)p)
#define YUME_SOCKS5_TCP_IFACE(p) ((YumeSocks5TCPIface *)p)
#define YUME_SOCKS5_TCP_TYPE (yume_socks5_tcp_iface ())

typedef void YumeSocks5TCP;
typedef struct _YumeSocks5TCPIface YumeSocks5TCPIface;

struct _YumeSocks5TCPIface
{
    int (*splicer) (YumeSocks5TCP *self, int fd);
};

void *yume_socks5_tcp_iface (void);

int yume_socks5_tcp_splice (YumeSocks5TCP *self, int fd);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_SOCKS5_TCP_H__ */
