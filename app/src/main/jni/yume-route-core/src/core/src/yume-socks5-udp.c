/*
 ============================================================================
 Name        : yume-socks5-udp.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 UDP
 ============================================================================
 */

#define _GNU_SOURCE
#include <errno.h>
#include <string.h>
#include <unistd.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-memory-allocator.h>

#include "yume-socks5.h"
#include "yume-socks5-misc-priv.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-udp.h"

#define UDP_BUF_SIZE 1500

static int
task_io_yielder (YumeTaskYieldType type, void *data)
{
    YumeSocks5 *self = data;

    if (self->type == YUME_SOCKS5_TYPE_UDP_IN_UDP) {
        ssize_t res;
        char buf;

        res = recv (self->fd, &buf, sizeof (buf), 0);
        if ((res == 0) || ((res < 0) && (errno != EAGAIN))) {
            yume_socks5_set_timeout (self, 0);
            return -1;
        }
    }

    return yume_socks5_task_io_yielder (type, data);
}

int
yume_socks5_udp_get_fd (YumeSocks5UDP *self)
{
    YumeSocks5UDPIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_UDP_TYPE);
    return iface->get_fd (self);
}

static int
yume_socks5_udp_sendmmsg_tcp (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num)
{
    struct iovec iov[num * 3];
    YumeSocks5UDPHdr udp[num];
    struct msghdr mh;
    int i, res;

    mh.msg_name = NULL;
    mh.msg_namelen = 0;
    mh.msg_control = NULL;
    mh.msg_controllen = 0;
    mh.msg_iov = iov;
    mh.msg_iovlen = num * 3;

    for (i = 0; i < num; i++) {
        int addrlen;

        addrlen = yume_socks5_addr_len (msgv[i].addr);
        if (addrlen <= 0) {
            LOG_D ("%p socks5 udp addr", self);
            return -1;
        }

        udp[i].datlen = htons (msgv[i].len);
        udp[i].hdrlen = 3 + addrlen;

        iov[i * 3].iov_base = &udp[i];
        iov[i * 3].iov_len = 3;
        iov[i * 3 + 1].iov_base = msgv[i].addr;
        iov[i * 3 + 1].iov_len = addrlen;
        iov[i * 3 + 2].iov_base = msgv[i].buf;
        iov[i * 3 + 2].iov_len = msgv[i].len;
    }

    res = yume_task_io_socket_sendmsg (yume_socks5_udp_get_fd (self), &mh,
                                      MSG_WAITALL, task_io_yielder, self);
    if (res <= 0) {
        LOG_D ("%p socks5 udp write tcp", self);
        return -1;
    }

    return num;
}

static int
yume_socks5_udp_sendmmsg_udp (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num)
{
    struct iovec iov[num * 3];
    struct mmsghdr mvec[num];
    YumeSocks5UDPHdr udp[num];
    int i, res;

    for (i = 0; i < num; i++) {
        int addrlen;

        addrlen = yume_socks5_addr_len (msgv[i].addr);
        if (addrlen <= 0) {
            LOG_D ("%p socks5 udp addr", self);
            return -1;
        }

        udp[i].datlen = 0;
        udp[i].hdrlen = 0;

        iov[i * 3].iov_base = &udp[i];
        iov[i * 3].iov_len = 3;
        iov[i * 3 + 1].iov_base = msgv[i].addr;
        iov[i * 3 + 1].iov_len = addrlen;
        iov[i * 3 + 2].iov_base = msgv[i].buf;
        iov[i * 3 + 2].iov_len = msgv[i].len;

        mvec[i].msg_hdr.msg_name = NULL;
        mvec[i].msg_hdr.msg_namelen = 0;
        mvec[i].msg_hdr.msg_control = NULL;
        mvec[i].msg_hdr.msg_controllen = 0;
        mvec[i].msg_hdr.msg_iov = &iov[i * 3];
        mvec[i].msg_hdr.msg_iovlen = 3;
    }

    res = yume_task_io_socket_sendmmsg (yume_socks5_udp_get_fd (self), mvec, num,
                                       MSG_WAITALL, task_io_yielder, self);
    if (res <= 0)
        LOG_D ("%p socks5 udp write udp", self);

    return res;
}

int
yume_socks5_udp_sendmmsg (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                         unsigned int num)
{
    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        return yume_socks5_udp_sendmmsg_tcp (self, msgv, num);
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        return yume_socks5_udp_sendmmsg_udp (self, msgv, num);
    default:
        return -1;
    }
}

static int
yume_socks5_udp_recvmmsg_tcp (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num, int nonblock)
{
    int i, fd, rlen = 0;

    fd = yume_socks5_udp_get_fd (self);

    if (nonblock)
        nonblock = MSG_DONTWAIT;

    for (i = 0; i < num; i++) {
        YumeSocks5UDPHdr udp;
        struct iovec iov[2];
        struct msghdr mh;
        int addrlen;
        int res;

        res = yume_task_io_socket_recv (fd, &udp, 5, nonblock, task_io_yielder,
                                       self);
        if (res > 0 && res < 5)
            res += yume_task_io_socket_recv (fd, (void *)&udp + res, 5 - res,
                                            MSG_WAITALL, task_io_yielder, self);
        if (res != 5) {
            if (rlen > 0)
                break;
            if (res != -1 || errno != EAGAIN)
                LOG_D ("%p socks5 udp read udp head", self);
            return res;
        }

        if (udp.hdrlen < 5) {
            LOG_D ("%p socks5 udp head len", self);
            return -1;
        }

        addrlen = udp.hdrlen - 3;
        udp.datlen = ntohs (udp.datlen);
        if (udp.datlen > (msgv[i].len - addrlen)) {
            LOG_D ("%p socks5 udp data len", self);
            return -1;
        }

        mh.msg_name = NULL;
        mh.msg_namelen = 0;
        mh.msg_control = NULL;
        mh.msg_controllen = 0;
        mh.msg_iov = iov;
        mh.msg_iovlen = 2;

        iov[0].iov_base = msgv[i].buf + 2;
        iov[0].iov_len = addrlen - 2;
        iov[1].iov_base = msgv[i].buf + addrlen;
        iov[1].iov_len = udp.datlen;

        res = yume_task_io_socket_recvmsg (fd, &mh, MSG_WAITALL, task_io_yielder,
                                          self);
        if (res != (addrlen - 2 + udp.datlen)) {
            LOG_D ("%p socks5 udp read udp data", self);
            return res;
        }

        msgv[i].addr = msgv[i].buf;
        msgv[i].buf = iov[1].iov_base;
        msgv[i].len = udp.datlen;
        msgv[i].addr->atype = udp.addr.atype;
        msgv[i].addr->domain.len = udp.addr.domain.len;

        rlen++;
    }

    return rlen;
}

static int
yume_socks5_udp_recvmmsg_udp (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                             unsigned int num, int nonblock)
{
    struct sockaddr_in6 taddr;
    struct mmsghdr mvec[num];
    struct iovec iov[num];
    int i, fd, res;

    fd = yume_socks5_udp_get_fd (self);

    if (nonblock)
        nonblock = MSG_DONTWAIT;

    for (i = 0; i < num; i++) {
        mvec[i].msg_hdr.msg_name = NULL;
        mvec[i].msg_hdr.msg_namelen = 0;
        mvec[i].msg_hdr.msg_control = NULL;
        mvec[i].msg_hdr.msg_controllen = 0;
        mvec[i].msg_hdr.msg_iov = &iov[i];
        mvec[i].msg_hdr.msg_iovlen = 1;

        iov[i].iov_base = msgv[i].buf;
        iov[i].iov_len = msgv[i].len;
    }

    if (!YUME_SOCKS5 (self)->udp_associated) {
        mvec[0].msg_hdr.msg_name = &taddr;
        mvec[0].msg_hdr.msg_namelen = sizeof (taddr);
    }

    res = yume_task_io_socket_recvmmsg (fd, mvec, num, nonblock, task_io_yielder,
                                       self);
    if (res <= 0) {
        if (res != -1 || errno != EAGAIN)
            LOG_D ("%p socks5 udp read udp", self);
        return res;
    }

    if (!YUME_SOCKS5 (self)->udp_associated) {
        struct sockaddr *saddr = mvec[0].msg_hdr.msg_name;
        socklen_t alen = mvec[0].msg_hdr.msg_namelen;
        if (connect (fd, saddr, alen) < 0)
            return -1;
        YUME_SOCKS5 (self)->udp_associated = 1;
    }

    for (i = 0; i < res; i++) {
        YumeSocks5UDPHdr *udp = msgv[i].buf;
        int addrlen = yume_socks5_addr_len (&udp->addr);
        int doff;

        msgv[i].len = mvec[i].msg_len;
        if (msgv[i].len < 4) {
            msgv[i].addr = NULL;
            msgv[i].len = 0;
            continue;
        }

        if (addrlen <= 0) {
            LOG_D ("%p socks5 udp addr", self);
            return -1;
        }

        doff = 3 + addrlen;
        if (doff > msgv[i].len) {
            LOG_D ("%p socks5 udp data len", self);
            return -1;
        }

        msgv[i].addr = &udp->addr;
        msgv[i].buf += doff;
        msgv[i].len -= doff;
    }

    return res;
}

int
yume_socks5_udp_recvmmsg (YumeSocks5UDP *self, YumeSocks5UDPMsg *msgv,
                         unsigned int num, int nonblock)
{
    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        return yume_socks5_udp_recvmmsg_tcp (self, msgv, num, nonblock);
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        return yume_socks5_udp_recvmmsg_udp (self, msgv, num, nonblock);
    default:
        return -1;
    }
}

static int
yume_socks5_udp_fwd_f (YumeSocks5UDP *self, int fd, void *buf, unsigned int num,
                      int *bind)
{
    YumeSocks5UDPMsg svec[num];
    int i, res;

    for (i = 0; i < num; i++) {
        svec[i].buf = buf + UDP_BUF_SIZE * i;
        svec[i].len = UDP_BUF_SIZE;
    }

    res = yume_socks5_udp_recvmmsg (self, svec, num, 1);
    if (res > 0) {
        struct sockaddr_in6 addr[res];
        struct mmsghdr dvec[res];
        struct iovec iov[res];
        int ret;

        for (i = 0; i < res; i++) {
            int family;

            if (!svec[i].len || !svec[i].addr) {
                LOG_D ("%p socks5 udp invalid", self);
                return -1;
            }

            family = yume_socks5_get_addr_family (YUME_SOCKS5 (self));
            ret = yume_socks5_addr_into_sockaddr6 (svec[i].addr, &addr[i],
                                                  &family);
            if (ret < 0) {
                LOG_D ("%p socks5 udp sockaddr", self);
                return -1;
            }

            dvec[i].msg_hdr.msg_name = (struct sockaddr *)&addr[i];
            dvec[i].msg_hdr.msg_namelen = sizeof (struct sockaddr_in6);
            dvec[i].msg_hdr.msg_control = NULL;
            dvec[i].msg_hdr.msg_controllen = 0;
            dvec[i].msg_hdr.msg_iov = &iov[i];
            dvec[i].msg_hdr.msg_iovlen = 1;
            iov[i].iov_base = svec[i].buf;
            iov[i].iov_len = svec[i].len;
        }

        if (!*bind) {
            YumeSocks5Class *skptr = YUME_OBJECT_GET_CLASS (self);
            struct sockaddr *addr = dvec[0].msg_hdr.msg_name;
            ret = skptr->binder (YUME_SOCKS5 (self), fd, addr);
            if (ret < 0) {
                LOG_W ("%p socks5 udp bind", self);
                return -1;
            }
            *bind = 1;
        }

        res = yume_task_io_socket_sendmmsg (fd, dvec, res, MSG_WAITALL,
                                           task_io_yielder, self);
    }
    if (res <= 0) {
        if (res == -1 && errno == EAGAIN)
            return 0;
        LOG_D ("%p socks5 udp fwd f recv send", self);
        return -1;
    }

    return 1;
}

static int
yume_socks5_udp_fwd_b (YumeSocks5UDP *self, int fd, struct mmsghdr *svec,
                      unsigned int num)
{
    int i, res;

    res = yume_task_io_socket_recvmmsg (fd, svec, num, MSG_DONTWAIT,
                                       task_io_yielder, self);
    if (res > 0) {
        YumeSocks5UDPMsg dvec[res];
        char saddr[res][19];

        for (i = 0; i < res; i++) {
            dvec[i].buf = svec[i].msg_hdr.msg_iov->iov_base;
            dvec[i].len = svec[i].msg_len;
            dvec[i].addr = (YumeSocks5Addr *)&saddr[i];
            yume_socks5_addr_from_sockaddr6 (dvec[i].addr,
                                            svec[i].msg_hdr.msg_name);
        }
        res = yume_socks5_udp_sendmmsg (self, dvec, res);
    }
    if (res <= 0) {
        if (res == -1 && errno == EAGAIN)
            return 0;
        LOG_D ("%p socks5 udp fwd b recv send", self);
        return -1;
    }

    return 1;
}

static int
yume_socks5_udp_splicer (YumeSocks5UDP *self, int fd_b)
{
    YumeTask *task = yume_task_self ();
    int res_f = 1, res_b = 1;
    int bind = 0;
    void *buf;
    int fd_a;
    int num;

    LOG_D ("%p socks5 udp splicer", self);

    num = yume_socks5_get_udp_copy_buffer_nums ();
    buf = yume_malloc (UDP_BUF_SIZE * num * 2);
    if (!buf)
        return -1;

    fd_a = yume_socks5_udp_get_fd (self);
    if (yume_task_mod_fd (task, fd_a, POLLIN | POLLOUT) < 0)
        yume_task_add_fd (task, fd_a, POLLIN | POLLOUT);
    if (yume_task_add_fd (task, fd_b, POLLIN | POLLOUT) < 0)
        yume_task_mod_fd (task, fd_b, POLLIN | POLLOUT);

    {
        struct mmsghdr vec[num];
        struct sockaddr_in6 addr[num];
        struct iovec iov[num];
        int i;

        for (i = 0; i < num; i++) {
            vec[i].msg_hdr.msg_name = (struct sockaddr *)&addr[i];
            vec[i].msg_hdr.msg_namelen = sizeof (struct sockaddr_in6);
            vec[i].msg_hdr.msg_control = NULL;
            vec[i].msg_hdr.msg_controllen = 0;
            vec[i].msg_hdr.msg_iov = &iov[i];
            vec[i].msg_hdr.msg_iovlen = 1;
            iov[i].iov_base = buf + UDP_BUF_SIZE * (num + i);
            iov[i].iov_len = UDP_BUF_SIZE;
        }

        for (;;) {
            YumeTaskYieldType type;

            if (res_f >= 0)
                res_f = yume_socks5_udp_fwd_f (self, fd_b, buf, num, &bind);
            if (res_b >= 0)
                res_b = yume_socks5_udp_fwd_b (self, fd_b, vec, num);

            if (res_f > 0 || res_b > 0)
                type = YUME_TASK_YIELD;
            else if ((res_f & res_b) == 0)
                type = YUME_TASK_WAITIO;
            else
                break;

            if (task_io_yielder (type, self))
                break;
        }
    }

    yume_free (buf);

    return 0;
}

int
yume_socks5_udp_splice (YumeSocks5UDP *self, int fd)
{
    YumeSocks5UDPIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_UDP_TYPE);
    return iface->splicer (self, fd);
}

void *
yume_socks5_udp_iface (void)
{
    static YumeSocks5UDPIface type = {
        .splicer = yume_socks5_udp_splicer,
    };

    return &type;
}
