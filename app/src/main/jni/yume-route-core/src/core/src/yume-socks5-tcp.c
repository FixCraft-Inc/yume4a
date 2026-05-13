/*
 ============================================================================
 Name        : yume-socks5-tcp.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 hev
 Description : Socks5 TCP
 ============================================================================
 */

#include "yume-socks5.h"
#include "yume-socks5-misc-priv.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-tcp.h"

#define task_io_yielder yume_socks5_task_io_yielder

static int
yume_socks5_tcp_splicer (YumeSocks5TCP *self, int fd)
{
    YumeTask *task = yume_task_self ();
    int cfd;
    int res;

    LOG_D ("%p socks5 tcp splicer", self);

    cfd = YUME_SOCKS5 (self)->fd;
    if (cfd < 0)
        return -1;

    res = yume_task_add_fd (task, fd, POLLIN | POLLOUT);
    if (res < 0)
        yume_task_mod_fd (task, fd, POLLIN | POLLOUT);

    yume_task_io_splice (cfd, cfd, fd, fd, 8192, task_io_yielder, self);

    return 0;
}

int
yume_socks5_tcp_splice (YumeSocks5TCP *self, int fd)
{
    YumeSocks5TCPIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_TCP_TYPE);
    return iface->splicer (self, fd);
}

void *
yume_socks5_tcp_iface (void)
{
    static YumeSocks5TCPIface type = {
        .splicer = yume_socks5_tcp_splicer,
    };

    return &type;
}
