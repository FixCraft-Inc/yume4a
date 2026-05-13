/*
 ============================================================================
 Name        : yume-socks5-server.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Server
 ============================================================================
 */

#include <string.h>
#include <unistd.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-memory-allocator.h>

#include "yume-socks5-proto.h"
#include "yume-socks5-misc-priv.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-server.h"

#define task_io_yielder yume_socks5_task_io_yielder

YumeSocks5Server *
yume_socks5_server_new (int fd)
{
    YumeSocks5Server *self;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5Server));
    if (!self)
        return NULL;

    res = yume_socks5_server_construct (self, fd);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 server new", self);

    return self;
}

void
yume_socks5_server_set_auth (YumeSocks5Server *self, YumeSocks5Authenticator *auth)
{
    if (self->auth)
        yume_object_unref (YUME_OBJECT (self->auth));

    yume_object_ref (YUME_OBJECT (auth));
    self->auth = auth;
}

static int
yume_socks5_server_read_auth_method (YumeSocks5Server *self)
{
    YumeSocks5Auth auth;
    YumeSocks5AuthMethod method;
    int res;
    int i;

    LOG_D ("%p socks5 server read auth method", self);

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &auth, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res != 2) {
        LOG_I ("%p socks5 server read auth method", self);
        return -1;
    }

    if (auth.ver != YUME_SOCKS5_VERSION_5) {
        LOG_I ("%p socks5 server auth.ver %u", self, auth.ver);
        return -1;
    }

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &auth.methods,
                                   auth.method_len, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res != auth.method_len) {
        LOG_I ("%p socks5 server read auth methods", self);
        return -1;
    }

    if (self->auth)
        method = YUME_SOCKS5_AUTH_METHOD_USER;
    else
        method = YUME_SOCKS5_AUTH_METHOD_NONE;

    res = -1;
    for (i = 0; i < auth.method_len; i++) {
        if (auth.methods[i] == method) {
            res = method;
            break;
        }
    }

    return res;
}

static int
yume_socks5_server_write_auth_method (YumeSocks5Server *self, int auth_method)
{
    YumeSocks5Auth auth;
    int res;

    LOG_D ("%p socks5 server write auth method", self);

    auth.ver = YUME_SOCKS5_VERSION_5;
    auth.method = auth_method;

    res = yume_task_io_socket_send (YUME_SOCKS5 (self)->fd, &auth, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res <= 0) {
        LOG_I ("%p socks5 server write auth method", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_server_read_auth_user (YumeSocks5Server *self)
{
    YumeSocks5User *user;
    uint8_t nlen, plen;
    uint8_t name[257];
    uint8_t pass[257];
    uint8_t head[2];
    int res;

    LOG_D ("%p socks5 server read auth user", self);

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, head, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res != 2) {
        LOG_I ("%p socks5 server read auth user.ver", self);
        return -1;
    }

    if (head[0] != 1) {
        LOG_I ("%p socks5 server auth user.ver %u", self, head[0]);
        return -1;
    }

    nlen = head[1];
    if (nlen == 0) {
        LOG_I ("%p socks5 server auth user.nlen %u", self, nlen);
        return -1;
    }

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, name, nlen + 1,
                                   MSG_WAITALL, task_io_yielder, self);
    if (res != (nlen + 1)) {
        LOG_I ("%p socks5 server read auth user.name", self);
        return -1;
    }

    plen = name[nlen];
    if (plen == 0) {
        LOG_I ("%p socks5 server auth user.plen %u", self, plen);
        return -1;
    }

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, pass, plen,
                                   MSG_WAITALL, task_io_yielder, self);
    if (res != plen) {
        LOG_I ("%p socks5 server read auth user.pass", self);
        return -1;
    }

    user = yume_socks5_authenticator_get (self->auth, (char *)name, nlen);
    if (!user) {
        LOG_I ("%p socks5 server auth user: %s pass: %s", self, name, pass);
        return -1;
    }

    res = yume_socks5_user_check (user, (char *)pass, plen);
    if (res < 0) {
        LOG_I ("%p socks5 server auth user: %s pass: %s", self, name, pass);
        return -1;
    }

    yume_object_ref (YUME_OBJECT (user));
    yume_object_unref (YUME_OBJECT (self->auth));
    self->user = user;

    return 0;
}

static int
yume_socks5_server_write_auth_user (YumeSocks5Server *self, int auth_res)
{
    uint8_t buf[2];
    int res;

    LOG_D ("%p socks5 server write auth user", self);

    buf[0] = 1;
    buf[1] = auth_res;

    res = yume_task_io_socket_send (YUME_SOCKS5 (self)->fd, buf, 2, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res <= 0) {
        LOG_I ("%p socks5 server write auth user", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_server_auth (YumeSocks5Server *self)
{
    int method;
    int res;

    method = yume_socks5_server_read_auth_method (self);
    res = yume_socks5_server_write_auth_method (self, method);
    if (res < 0)
        return -1;

    switch (method) {
    case YUME_SOCKS5_AUTH_METHOD_NONE:
        break;
    case YUME_SOCKS5_AUTH_METHOD_USER:
        res = yume_socks5_server_read_auth_user (self);
        res |= yume_socks5_server_write_auth_user (self, res);
        if (res < 0)
            return -1;
        break;
    default:
        return -1;
    }

    return 0;
}

static int
yume_socks5_server_read_request (YumeSocks5Server *self, int *cmd, int *rep,
                                struct sockaddr_in6 *addr)
{
    YumeSocks5ReqRes req;
    int addr_family;
    int addrlen;
    int res;

    LOG_D ("%p socks5 server read request", self);

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, &req, 5, MSG_WAITALL,
                                   task_io_yielder, self);
    if (res != 5) {
        LOG_I ("%p socks5 server read request", self);
        return -1;
    }

    if (req.ver != YUME_SOCKS5_VERSION_5) {
        *rep = YUME_SOCKS5_RES_REP_FAIL;
        LOG_I ("%p socks5 server req.ver %u", self, req.ver);
        return 0;
    }

    switch (req.addr.atype) {
    case YUME_SOCKS5_ADDR_TYPE_IPV4:
        addrlen = 5;
        break;
    case YUME_SOCKS5_ADDR_TYPE_IPV6:
        addrlen = 17;
        break;
    case YUME_SOCKS5_ADDR_TYPE_NAME:
        addrlen = 2 + req.addr.domain.len;
        break;
    default:
        *rep = YUME_SOCKS5_RES_REP_ADDR;
        LOG_I ("%p socks5 server req.atype %u", self, req.addr.atype);
        return 0;
    }

    res = yume_task_io_socket_recv (YUME_SOCKS5 (self)->fd, req.addr.domain.addr,
                                   addrlen, MSG_WAITALL, task_io_yielder, self);
    if (res != addrlen) {
        *rep = YUME_SOCKS5_RES_REP_ADDR;
        LOG_I ("%p socks5 server read addr", self);
        return 0;
    }

    addr_family = yume_socks5_get_addr_family (YUME_SOCKS5 (self));
    res = yume_socks5_addr_into_sockaddr6 (&req.addr, addr, &addr_family);
    if (res < 0) {
        *rep = YUME_SOCKS5_RES_REP_ADDR;
        LOG_I ("%p socks5 server resolve addr", self);
        return 0;
    }
    yume_socks5_set_addr_family (YUME_SOCKS5 (self), addr_family);

    if (LOG_ON ()) {
        const char *type;
        const char *str;
        char buf[272];

        switch (req.cmd) {
        case YUME_SOCKS5_REQ_CMD_CONNECT:
            type = "tcp";
            break;
        case YUME_SOCKS5_REQ_CMD_UDP_ASC:
        case YUME_SOCKS5_REQ_CMD_FWD_UDP:
            type = "udp";
            break;
        default:
            type = "unknown";
            break;
        }

        str = yume_socks5_addr_into_str (&req.addr, buf, sizeof (buf));
        LOG_I ("%p socks5 server %s %s", self, type, str);
    }

    *cmd = req.cmd;

    return 0;
}

static int
yume_socks5_server_write_response (YumeSocks5Server *self, int rep,
                                  struct sockaddr_in6 *addr)
{
    YumeSocks5ReqRes res;
    int ret;

    LOG_D ("%p socks5 server write response", self);

    res.ver = YUME_SOCKS5_VERSION_5;
    res.rep = rep;
    res.rsv = 0;

    ret = yume_socks5_addr_from_sockaddr6 (&res.addr, addr);
    ret = yume_task_io_socket_send (YUME_SOCKS5 (self)->fd, &res, 3 + ret,
                                   MSG_WAITALL, task_io_yielder, self);
    if (ret <= 0) {
        LOG_I ("%p socks5 server write response", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_server_connect (YumeSocks5Server *self, struct sockaddr_in6 *addr)
{
    YumeSocks5Class *klass;
    int timeout;
    int res;
    int fd;

    LOG_D ("%p socks5 server connect", self);

    fd = yume_socks5_socket (SOCK_STREAM);
    if (fd < 0) {
        LOG_E ("%p socks5 server socket stream", self);
        return -1;
    }

    klass = YUME_OBJECT_GET_CLASS (self);
    res = klass->binder (YUME_SOCKS5 (self), fd, (struct sockaddr *)addr);
    if (res < 0) {
        LOG_W ("%p socks5 server bind", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    timeout = yume_socks5_get_connect_timeout ();
    yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);

    res = yume_task_io_socket_connect (fd, (struct sockaddr *)addr,
                                      sizeof (*addr), task_io_yielder, self);
    if (res < 0) {
        LOG_I ("%p socks5 server connect", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    timeout = yume_socks5_get_tcp_timeout ();
    yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);

    self->fds[0] = fd;

    return 0;
}

static int
yume_socks5_server_bind (YumeSocks5Server *self, struct sockaddr_in6 *addr)
{
    YumeSocks5ServerClass *sskptr = YUME_OBJECT_GET_CLASS (self);
    int one = 1;
    int res;
    int fd;

    LOG_D ("%p socks5 server bind", self);

    fd = yume_socks5_socket (SOCK_DGRAM);
    if (fd < 0) {
        LOG_E ("%p socks5 server socket dgram", self);
        return -1;
    }

    self->fds[0] = fd;

    if (!addr)
        return 0;

    fd = yume_socks5_socket (SOCK_DGRAM);
    if (fd < 0) {
        LOG_E ("%p socks5 server socket dgram", self);
        return -1;
    }

    res = setsockopt (fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof (one));
    if (res < 0) {
        LOG_W ("%p socks5 server socket reuse", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    res = sskptr->binder (self, fd, addr);
    if (res < 0) {
        LOG_W ("%p socks5 server bind", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    self->fds[1] = fd;

    return 0;
}

static int
yume_socks5_server_udp_bind (YumeSocks5Server *self, int sock,
                            struct sockaddr_in6 *src)
{
    socklen_t alen;
    int res;

    LOG_D ("%p socks5 server udp bind", self);

    alen = sizeof (struct sockaddr_in6);
    res = getsockname (YUME_SOCKS5 (self)->fd, (struct sockaddr *)src, &alen);
    if (res < 0) {
        LOG_W ("%p socks5 server tcp socket name", self);
        return -1;
    }

    src->sin6_port = 0;
    res = bind (sock, (struct sockaddr *)src, alen);
    if (res < 0) {
        LOG_W ("%p socks5 server socket bind", self);
        return -1;
    }

    res = getsockname (sock, (struct sockaddr *)src, &alen);
    if (res < 0) {
        LOG_W ("%p socks5 server udp socket name", self);
        return -1;
    }

    return 0;
}

static int
yume_socks5_server_handshake (YumeSocks5Server *self)
{
    struct sockaddr_in6 addr;
    int timeout;
    int cmd;
    int rep;
    int res;

    LOG_D ("%p socks5 server handshake", self);

    timeout = yume_socks5_get_tcp_timeout ();
    yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);

    res = yume_socks5_server_auth (self);
    if (res < 0)
        return -1;

    rep = YUME_SOCKS5_RES_REP_SUCC;
    res = yume_socks5_server_read_request (self, &cmd, &rep, &addr);
    if (res < 0)
        return -1;

    if (rep == YUME_SOCKS5_RES_REP_SUCC) {
        switch (cmd) {
        case YUME_SOCKS5_REQ_CMD_CONNECT:
            res = yume_socks5_server_connect (self, &addr);
            if (res < 0)
                rep = YUME_SOCKS5_RES_REP_HOST;
            YUME_SOCKS5 (self)->type = YUME_SOCKS5_TYPE_TCP;
            break;
        case YUME_SOCKS5_REQ_CMD_UDP_ASC:
            res = yume_socks5_server_bind (self, &addr);
            if (res < 0)
                rep = YUME_SOCKS5_RES_REP_FAIL;
            YUME_SOCKS5 (self)->type = YUME_SOCKS5_TYPE_UDP_IN_UDP;
            break;
        case YUME_SOCKS5_REQ_CMD_FWD_UDP:
            res = yume_socks5_server_bind (self, NULL);
            if (res < 0)
                rep = YUME_SOCKS5_RES_REP_FAIL;
            YUME_SOCKS5 (self)->type = YUME_SOCKS5_TYPE_UDP_IN_TCP;
            break;
        default:
            rep = YUME_SOCKS5_RES_REP_IMPL;
            break;
        }
    }

    res = yume_socks5_server_write_response (self, rep, &addr);
    if ((res < 0) || (rep != YUME_SOCKS5_RES_REP_SUCC))
        return -1;

    return 0;
}

static int
yume_socks5_server_service (YumeSocks5Server *self)
{
    int timeout;

    LOG_D ("%p socks5 server service", self);

    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_TCP:
        yume_socks5_tcp_splice (YUME_SOCKS5_TCP (self), self->fds[0]);
        break;
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        timeout = yume_socks5_get_udp_timeout ();
        yume_socks5_set_timeout (YUME_SOCKS5 (self), timeout);
        yume_socks5_udp_splice (YUME_SOCKS5_UDP (self), self->fds[0]);
        break;
    default:
        return -1;
    }

    return 0;
}

int
yume_socks5_server_run (YumeSocks5Server *self)
{
    YumeTask *task = yume_task_self ();
    int res;
    int fd;

    LOG_D ("%p socks5 server run", self);

    fd = YUME_SOCKS5 (self)->fd;
    res = yume_task_add_fd (task, fd, POLLIN | POLLOUT);
    if (res < 0)
        yume_task_mod_fd (task, fd, POLLIN | POLLOUT);

    res = yume_socks5_server_handshake (self);
    if (res < 0)
        return -1;

    res = yume_socks5_server_service (self);
    if (res < 0)
        return -1;

    return 0;
}

static int
yume_socks5_server_get_fd (YumeSocks5UDP *self)
{
    int fd;

    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        fd = YUME_SOCKS5 (self)->fd;
        break;
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        fd = YUME_SOCKS5_SERVER (self)->fds[1];
        break;
    default:
        return -1;
    }

    return fd;
}

int
yume_socks5_server_construct (YumeSocks5Server *self, int fd)
{
    int res;

    res = yume_socks5_construct (&self->base, YUME_SOCKS5_TYPE_NONE);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 server construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_SERVER_TYPE;

    YUME_SOCKS5 (self)->fd = fd;

    self->fds[0] = -1;
    self->fds[1] = -1;

    return 0;
}

static void
yume_socks5_server_destruct (YumeObject *base)
{
    YumeSocks5Server *self = YUME_SOCKS5_SERVER (base);
    YumeTask *task = yume_task_self ();

    LOG_D ("%p socks5 server destruct", self);

    if (self->fds[0] >= 0) {
        yume_task_del_fd (task, self->fds[0]);
        close (self->fds[0]);
    }
    if (self->fds[1] >= 0) {
        yume_task_del_fd (task, self->fds[1]);
        close (self->fds[1]);
    }

    if (self->obj)
        yume_object_unref (self->obj);

    YUME_SOCKS5_TYPE->destruct (base);
}

static void *
yume_socks5_server_iface (YumeObject *base, void *type)
{
    YumeSocks5ServerClass *klass = YUME_OBJECT_GET_CLASS (base);

    if (type == YUME_SOCKS5_TCP_TYPE)
        return &klass->tcp;

    if (type == YUME_SOCKS5_UDP_TYPE)
        return &klass->udp;

    return NULL;
}

YumeObjectClass *
yume_socks5_server_class (void)
{
    static YumeSocks5ServerClass klass;
    YumeSocks5ServerClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        YumeSocks5TCPIface *tiptr;
        YumeSocks5UDPIface *uiptr;

        memcpy (kptr, YUME_SOCKS5_TYPE, sizeof (YumeSocks5Class));

        okptr->name = "YumeSocks5Server";
        okptr->destruct = yume_socks5_server_destruct;
        okptr->iface = yume_socks5_server_iface;

        kptr->binder = yume_socks5_server_udp_bind;

        tiptr = &kptr->tcp;
        memcpy (tiptr, YUME_SOCKS5_TCP_TYPE, sizeof (YumeSocks5TCPIface));

        uiptr = &kptr->udp;
        memcpy (uiptr, YUME_SOCKS5_UDP_TYPE, sizeof (YumeSocks5UDPIface));
        uiptr->get_fd = yume_socks5_server_get_fd;
    }

    return okptr;
}
