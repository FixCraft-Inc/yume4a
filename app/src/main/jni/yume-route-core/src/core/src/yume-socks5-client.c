/*
 ============================================================================
 Name        : yume-socks5-client.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Client
 ============================================================================
 */

#include <string.h>
#include <unistd.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-memory-allocator.h>

#include "yume-socks5-misc-priv.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-client.h"

#define task_io_yielder yume_socks5_task_io_yielder

static int
yume_socks5_client_write_auth_methods (YumeSocks5Client *self)
{
    YumeSocks5Auth auth;
    int res;

    LOG_D ("%p socks5 client write auth methods", self);

    auth.ver = YUME_SOCKS5_VERSION_5;
    auth.method_len = 1;
    if (!self->auth.user || !self->auth.pass)
        auth.methods[0] = YUME_SOCKS5_AUTH_METHOD_NONE;
    else
        auth.methods[0] = YUME_SOCKS5_AUTH_METHOD_USER;

    res = yume_task_io_socket_send (YUME_SOCKS5 (self)->fd, &auth, 3, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res <= 0) {
        LOG_I ("%p socks5 client write auth methods", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_client_write_auth_creds (YumeSocks5Client *self)
{
    struct msghdr mh = { 0 };
    struct iovec iov[4];
    unsigned char ub[3];
    int res;

    LOG_D ("%p socks5 client write auth creds", self);

    if (!self->auth.user || !self->auth.pass)
        return 0;

    ub[0] = YUME_SOCKS5_AUTH_VERSION_1;
    ub[1] = strlen (self->auth.user);
    ub[2] = strlen (self->auth.pass);
    iov[0].iov_base = &ub[0];
    iov[0].iov_len = 2;
    iov[1].iov_base = (void *)self->auth.user;
    iov[1].iov_len = ub[1];
    iov[2].iov_base = &ub[2];
    iov[2].iov_len = 1;
    iov[3].iov_base = (void *)self->auth.pass;
    iov[3].iov_len = ub[2];

    mh.msg_iov = iov;
    mh.msg_iovlen = 4;
    res = yume_task_io_socket_sendmsg (YUME_SOCKS5 (self)->fd, &mh, MSG_WAITALL,
                                      task_io_yielder, self);
    if (res <= 0) {
        LOG_I ("%p socks5 client write auth creds", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_client_write_request (YumeSocks5Client *self)
{
    YumeSocks5ClientClass *klass;
    struct msghdr mh = { 0 };
    struct iovec iov[2];
    YumeSocks5Addr *addr;
    YumeSocks5ReqRes req;
    int addrlen;
    int ret;

    LOG_D ("%p socks5 client write request", self);

    req.ver = YUME_SOCKS5_VERSION_5;
    req.rsv = 0;

    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_TCP:
        req.cmd = YUME_SOCKS5_REQ_CMD_CONNECT;
        break;
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        req.cmd = YUME_SOCKS5_REQ_CMD_FWD_UDP;
        break;
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        req.cmd = YUME_SOCKS5_REQ_CMD_UDP_ASC;
        break;
    default:
        return -1;
    }

    iov[0].iov_base = &req;
    iov[0].iov_len = 3;

    klass = YUME_OBJECT_GET_CLASS (self);
    addr = klass->get_upstream_addr (self);

    switch (addr->atype) {
    case YUME_SOCKS5_ADDR_TYPE_IPV4:
        addrlen = 7;
        break;
    case YUME_SOCKS5_ADDR_TYPE_IPV6:
        addrlen = 19;
        break;
    case YUME_SOCKS5_ADDR_TYPE_NAME:
        addrlen = 4 + addr->domain.len;
        break;
    default:
        LOG_I ("%p socks5 client req.atype %u", self, addr->atype);
        return -1;
    }

    iov[1].iov_base = addr;
    iov[1].iov_len = addrlen;

    mh.msg_iov = iov;
    mh.msg_iovlen = 2;
    ret = yume_task_io_socket_sendmsg (YUME_SOCKS5 (self)->fd, &mh, MSG_WAITALL,
                                      task_io_yielder, self);
    if (ret <= 0) {
        LOG_I ("%p socks5 client write request", self);
        return -1;
    }

    yume_free (addr);

    return 0;
}

static int
yume_socks5_client_read_auth_method (YumeSocks5Client *self)
{
    YumeSocks5Auth auth;
    int res;

    LOG_D ("%p socks5 client read auth method", self);

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &auth, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res != 2) {
        LOG_I ("%p socks5 client read auth", self);
        return -1;
    }

    if (auth.ver != YUME_SOCKS5_VERSION_5) {
        LOG_I ("%p socks5 client auth.ver %u", self, auth.ver);
        return -1;
    }

    return auth.method;
}

static int
yume_socks5_client_read_auth_creds (YumeSocks5Client *self)
{
    YumeSocks5ReqRes res;
    int ret;

    LOG_D ("%p socks5 client read auth creds", self);

    ret = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &res, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (ret != 2) {
        LOG_I ("%p socks5 client read auth creds", self);
        return -1;
    }

    if (res.ver != YUME_SOCKS5_AUTH_VERSION_1) {
        LOG_I ("%p socks5 client auth.res.ver %u", self, res.ver);
        return -1;
    }

    if (res.rep != YUME_SOCKS5_RES_REP_SUCC) {
        LOG_I ("%p socks5 client auth.res.rep %u", self, res.rep);
        return -1;
    }

    LOG_D ("%p socks5 client auth done", self);

    return 0;
}

static int
yume_socks5_client_read_response (YumeSocks5Client *self)
{
    YumeSocks5ClientClass *klass;
    YumeSocks5ReqRes res;
    int addrlen;
    int ret;

    LOG_D ("%p socks5 client read response", self);

    ret = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &res, 4, MSG_WAITALL,
                                   task_io_yielder, self);
    if (ret != 4) {
        LOG_I ("%p socks5 client read response", self);
        return -1;
    }

    if (res.ver != YUME_SOCKS5_VERSION_5) {
        LOG_I ("%p socks5 client res.ver %u", self, res.ver);
        return -1;
    }

    if (res.rep != YUME_SOCKS5_RES_REP_SUCC) {
        LOG_I ("%p socks5 client res.rep %u", self, res.rep);
        return -1;
    }

    switch (res.addr.atype) {
    case YUME_SOCKS5_ADDR_TYPE_IPV4:
        addrlen = 6;
        break;
    case YUME_SOCKS5_ADDR_TYPE_IPV6:
        addrlen = 18;
        break;
    default:
        LOG_I ("%p socks5 client res.atype %u", self, res.addr.atype);
        return -1;
    }

    ret = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &res.addr.ipv4,
                                   addrlen, MSG_WAITALL, task_io_yielder, self);
    if (ret != addrlen) {
        LOG_I ("%p socks5 client read addr", self);
        return -1;
    }

    klass = YUME_OBJECT_GET_CLASS (self);
    ret = klass->set_upstream_addr (self, &res.addr);
    if (ret < 0) {
        LOG_W ("%p socks5 client set upstream addr", self);
        return -1;
    }

    return 0;
}

int
yume_socks5_client_connect (YumeSocks5Client *self, const char *addr, int port)
{
    YumeSocks5Class *klass;
    struct sockaddr_in6 saddr;
    struct sockaddr *sap;
    int addr_family;
    int timeout;
    int fd, res;

    LOG_D ("%p socks5 client connect [%s]:%d", self, addr, port);

    timeout = yume_socks5_get_connect_timeout ();
    yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);

    addr_family = yume_socks5_get_addr_family (YUME_SOCKS5 (self));
    res = yume_socks5_name_into_sockaddr6 (addr, port, &saddr, &addr_family);
    if (res < 0) {
        LOG_I ("%p socks5 client resolve [%s]:%d", self, addr, port);
        return -1;
    }

    fd = yume_socks5_socket (SOCK_STREAM);
    if (fd < 0) {
        LOG_E ("%p socks5 client socket", self);
        return -1;
    }

    sap = (struct sockaddr *)&saddr;
    klass = YUME_OBJECT_GET_CLASS (self);
    res = klass->binder (YUME_SOCKS5 (self), fd, sap);
    if (res < 0) {
        LOG_W ("%p socks5 client bind", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    res = yume_task_io_socket_connect (fd, sap, sizeof (saddr), task_io_yielder,
                                      self);
    if (res < 0) {
        LOG_I ("%p socks5 client connect", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    YUME_SOCKS5 (self)->fd = fd;
    yume_socks5_set_addr_family (YUME_SOCKS5 (self), addr_family);
    LOG_D ("%p socks5 client connect server fd %d", self, fd);

    return 0;
}

static int
yume_socks5_client_handshake_standard (YumeSocks5Client *self)
{
    int res;

    LOG_D ("%p socks5 client handshake standard", self);

    res = yume_socks5_client_write_auth_methods (self);
    if (res < 0)
        return -1;

    res = yume_socks5_client_read_auth_method (self);
    if (res < 0)
        return -1;

    if (res == YUME_SOCKS5_AUTH_METHOD_USER) {
        res = yume_socks5_client_write_auth_creds (self);
        if (res < 0)
            return -1;

        res = yume_socks5_client_read_auth_creds (self);
        if (res < 0)
            return -1;
    } else if (res != YUME_SOCKS5_AUTH_METHOD_NONE) {
        LOG_I ("%p socks5 client auth method %d", self, res);
        return -1;
    }

    res = yume_socks5_client_write_request (self);
    if (res < 0)
        return -1;

    res = yume_socks5_client_read_response (self);
    if (res < 0)
        return -1;

    return 0;
}

static int
yume_socks5_client_handshake_pipeline (YumeSocks5Client *self)
{
    int res;

    LOG_D ("%p socks5 client handshake pipeline", self);

    res = yume_socks5_client_write_auth_methods (self);
    if (res < 0)
        return -1;

    res = yume_socks5_client_write_auth_creds (self);
    if (res < 0)
        return -1;

    res = yume_socks5_client_write_request (self);
    if (res < 0)
        return -1;

    res = yume_socks5_client_read_auth_method (self);
    if (res < 0)
        return -1;

    if (res == YUME_SOCKS5_AUTH_METHOD_USER) {
        res = yume_socks5_client_read_auth_creds (self);
        if (res < 0)
            return -1;
    } else if (res != YUME_SOCKS5_AUTH_METHOD_NONE) {
        LOG_I ("%p socks5 client auth method %d", self, res);
        return -1;
    }

    res = yume_socks5_client_read_response (self);
    if (res < 0)
        return -1;

    return 0;
}

int
yume_socks5_client_handshake (YumeSocks5Client *self, int pipeline)
{
    int timeout;
    int res;

    timeout = yume_socks5_get_tcp_timeout ();
    yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);

    if (pipeline)
        res = yume_socks5_client_handshake_pipeline (self);
    else
        res = yume_socks5_client_handshake_standard (self);

    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        timeout = yume_socks5_get_udp_timeout ();
        yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);
        break;
    default:
        break;
    }

    return res;
}

void
yume_socks5_client_set_auth (YumeSocks5Client *self, const char *user,
                            const char *pass)
{
    LOG_D ("%p socks5 client set auth", self);

    self->auth.user = user;
    self->auth.pass = pass;
}

int
yume_socks5_client_construct (YumeSocks5Client *self, YumeSocks5Type type)
{
    int res;

    res = yume_socks5_construct (&self->base, type);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 client construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_CLIENT_TYPE;

    return 0;
}

static void
yume_socks5_client_destruct (YumeObject *base)
{
    YumeSocks5Client *self = YUME_SOCKS5_CLIENT (base);

    LOG_D ("%p socks5 client destruct", self);

    YUME_SOCKS5_TYPE->destruct (base);
}

YumeObjectClass *
yume_socks5_client_class (void)
{
    static YumeSocks5ClientClass klass;
    YumeSocks5ClientClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_SOCKS5_TYPE, sizeof (YumeSocks5Class));

        okptr->name = "YumeSocks5Client";
        okptr->destruct = yume_socks5_client_destruct;
    }

    return okptr;
}
