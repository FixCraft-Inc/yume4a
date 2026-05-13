/*
 ============================================================================
 Name        : yume-socks5-client-udp.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Client UDP
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

#include "yume-socks5-client-udp.h"

#define task_io_yielder yume_socks5_task_io_yielder

YumeSocks5ClientUDP *
yume_socks5_client_udp_new (YumeSocks5Type type)
{
    YumeSocks5ClientUDP *self;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5ClientUDP));
    if (!self)
        return NULL;

    res = yume_socks5_client_udp_construct (self, type);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 client udp new", self);

    return self;
}

static YumeSocks5Addr *
yume_socks5_client_udp_get_upstream_addr (YumeSocks5Client *base)
{
    YumeSocks5AddrFamily family;
    YumeSocks5Addr *addr;

    family = yume_socks5_get_addr_family (YUME_SOCKS5 (base));

    switch (family) {
    case YUME_SOCKS5_ADDR_FAMILY_IPV4:
        addr = yume_malloc0 (7);
        if (addr)
            addr->atype = YUME_SOCKS5_ADDR_TYPE_IPV4;
        break;
    case YUME_SOCKS5_ADDR_FAMILY_IPV6:
        addr = yume_malloc0 (19);
        if (addr)
            addr->atype = YUME_SOCKS5_ADDR_TYPE_IPV6;
        break;
    default:
        addr = NULL;
    }

    return addr;
}

static int
yume_socks5_client_udp_set_upstream_addr (YumeSocks5Client *base,
                                         YumeSocks5Addr *addr)
{
    YumeSocks5ClientUDP *self = YUME_SOCKS5_CLIENT_UDP (base);
    struct sockaddr_in6 saddr;
    struct sockaddr *sadp;
    YumeSocks5Class *klass;
    int addr_family;
    int res;
    int fd;

    if (YUME_SOCKS5 (base)->type != YUME_SOCKS5_TYPE_UDP_IN_UDP)
        return 0;

    addr_family = yume_socks5_get_addr_family (YUME_SOCKS5 (self));
    res = yume_socks5_addr_into_sockaddr6 (addr, &saddr, &addr_family);
    if (res < 0) {
        LOG_W ("%p socks5 client udp addr", self);
        return -1;
    }

    fd = yume_socks5_socket (SOCK_DGRAM);
    if (fd < 0) {
        LOG_E ("%p socks5 client udp socket", self);
        return -1;
    }

    sadp = (struct sockaddr *)&saddr;
    klass = YUME_OBJECT_GET_CLASS (self);
    res = klass->binder (YUME_SOCKS5 (self), fd, sadp);
    if (res < 0) {
        LOG_W ("%p socks5 client udp bind", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    res = yume_task_io_socket_connect (fd, sadp, sizeof (saddr), task_io_yielder,
                                      self);
    if (res < 0) {
        LOG_I ("%p socks5 client udp connect", self);
        yume_task_del_fd (yume_task_self (), fd);
        close (fd);
        return -1;
    }

    YUME_SOCKS5 (self)->udp_associated = 1;
    self->fd = fd;

    return 0;
}

static int
yume_socks5_client_udp_get_fd (YumeSocks5UDP *self)
{
    int fd;

    switch (YUME_SOCKS5 (self)->type) {
    case YUME_SOCKS5_TYPE_UDP_IN_TCP:
        fd = YUME_SOCKS5 (self)->fd;
        break;
    case YUME_SOCKS5_TYPE_UDP_IN_UDP:
        fd = YUME_SOCKS5_CLIENT_UDP (self)->fd;
        break;
    default:
        return -1;
    }

    return fd;
}

int
yume_socks5_client_udp_construct (YumeSocks5ClientUDP *self, YumeSocks5Type type)
{
    int res;

    res = yume_socks5_client_construct (&self->base, type);
    if (res < 0)
        return res;

    LOG_I ("%p socks5 client udp construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_CLIENT_UDP_TYPE;

    self->fd = -1;

    return 0;
}

static void
yume_socks5_client_udp_destruct (YumeObject *base)
{
    YumeSocks5ClientUDP *self = YUME_SOCKS5_CLIENT_UDP (base);

    LOG_D ("%p socks5 client udp destruct", self);

    if (self->fd >= 0) {
        yume_task_del_fd (yume_task_self (), self->fd);
        close (self->fd);
    }

    YUME_SOCKS5_CLIENT_TYPE->destruct (base);
}

static void *
yume_socks5_client_udp_iface (YumeObject *base, void *type)
{
    YumeSocks5ClientUDPClass *klass = YUME_OBJECT_GET_CLASS (base);

    return &klass->udp;
}

YumeObjectClass *
yume_socks5_client_udp_class (void)
{
    static YumeSocks5ClientUDPClass klass;
    YumeSocks5ClientUDPClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        YumeSocks5ClientClass *ckptr;
        YumeSocks5UDPIface *uiptr;

        memcpy (kptr, YUME_SOCKS5_CLIENT_TYPE, sizeof (YumeSocks5ClientClass));

        okptr->name = "YumeSocks5ClientUDP";
        okptr->destruct = yume_socks5_client_udp_destruct;
        okptr->iface = yume_socks5_client_udp_iface;

        ckptr = YUME_SOCKS5_CLIENT_CLASS (kptr);
        ckptr->get_upstream_addr = yume_socks5_client_udp_get_upstream_addr;
        ckptr->set_upstream_addr = yume_socks5_client_udp_set_upstream_addr;

        uiptr = &kptr->udp;
        memcpy (uiptr, YUME_SOCKS5_UDP_TYPE, sizeof (YumeSocks5UDPIface));
        uiptr->get_fd = yume_socks5_client_udp_get_fd;
    }

    return okptr;
}
