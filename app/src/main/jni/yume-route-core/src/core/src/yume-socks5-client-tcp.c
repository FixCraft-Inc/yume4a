/*
 ============================================================================
 Name        : yume-socks5-client-tcp.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2025 hev
 Description : Socks5 Client TCP
 ============================================================================
 */

#include <string.h>

#include <yume-memory-allocator.h>

#include "yume-socks5-misc-priv.h"
#include "yume-socks5-logger-priv.h"

#include "yume-socks5-client-tcp.h"

YumeSocks5ClientTCP *
yume_socks5_client_tcp_new_name (const char *name, int port)
{
    YumeSocks5ClientTCP *self;
    YumeSocks5Addr addr;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5ClientTCP));
    if (!self)
        return NULL;

    yume_socks5_addr_from_name (&addr, name, port);
    res = yume_socks5_client_tcp_construct (self, &addr);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 client tcp new name", self);

    return self;
}

YumeSocks5ClientTCP *
yume_socks5_client_tcp_new_ipv4 (const void *ipv4, int port)
{
    YumeSocks5ClientTCP *self;
    YumeSocks5Addr addr;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5ClientTCP));
    if (!self)
        return NULL;

    yume_socks5_addr_from_ipv4 (&addr, ipv4, port);
    res = yume_socks5_client_tcp_construct (self, &addr);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 client tcp new ipv4", self);

    return self;
}

YumeSocks5ClientTCP *
yume_socks5_client_tcp_new_ipv6 (const void *ipv6, int port)
{
    YumeSocks5ClientTCP *self;
    YumeSocks5Addr addr;
    int res;

    self = yume_malloc0 (sizeof (YumeSocks5ClientTCP));
    if (!self)
        return NULL;

    yume_socks5_addr_from_ipv6 (&addr, ipv6, port);
    res = yume_socks5_client_tcp_construct (self, &addr);
    if (res < 0) {
        yume_free (self);
        return NULL;
    }

    LOG_D ("%p socks5 client tcp new ipv6", self);

    return self;
}

static YumeSocks5Addr *
yume_socks5_client_tcp_get_upstream_addr (YumeSocks5Client *base)
{
    YumeSocks5ClientTCP *self = YUME_SOCKS5_CLIENT_TCP (base);
    YumeSocks5Addr *addr;

    addr = self->addr;
    self->addr = NULL;

    return addr;
}

static int
yume_socks5_client_tcp_set_upstream_addr (YumeSocks5Client *base,
                                         YumeSocks5Addr *addr)
{
    return 0;
}

int
yume_socks5_client_tcp_construct (YumeSocks5ClientTCP *self,
                                 const YumeSocks5Addr *addr)
{
    int res;

    res = yume_socks5_client_construct (&self->base, YUME_SOCKS5_TYPE_TCP);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 client tcp construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_CLIENT_TCP_TYPE;

    res = yume_socks5_addr_len (addr);
    self->addr = yume_malloc (res);
    if (!self->addr)
        return -1;
    memcpy (self->addr, addr, res);

    if (LOG_ON ()) {
        const char *str;
        char buf[272];

        str = yume_socks5_addr_into_str (self->addr, buf, sizeof (buf));
        LOG_I ("%p socks5 client tcp -> %s", self, str);
    }

    return 0;
}

static void
yume_socks5_client_tcp_destruct (YumeObject *base)
{
    YumeSocks5ClientTCP *self = YUME_SOCKS5_CLIENT_TCP (base);

    LOG_D ("%p socks5 client tcp destruct", self);

    if (self->addr)
        yume_free (self->addr);

    YUME_SOCKS5_CLIENT_TYPE->destruct (base);
}

static void *
yume_socks5_client_tcp_iface (YumeObject *base, void *type)
{
    YumeSocks5ClientTCPClass *klass = YUME_OBJECT_GET_CLASS (base);

    return &klass->tcp;
}

YumeObjectClass *
yume_socks5_client_tcp_class (void)
{
    static YumeSocks5ClientTCPClass klass;
    YumeSocks5ClientTCPClass *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        YumeSocks5ClientClass *ckptr;
        YumeSocks5TCPIface *tiptr;

        memcpy (kptr, YUME_SOCKS5_CLIENT_TYPE, sizeof (YumeSocks5ClientClass));

        okptr->name = "YumeSocks5ClientTCP";
        okptr->destruct = yume_socks5_client_tcp_destruct;
        okptr->iface = yume_socks5_client_tcp_iface;

        ckptr = YUME_SOCKS5_CLIENT_CLASS (kptr);
        ckptr->get_upstream_addr = yume_socks5_client_tcp_get_upstream_addr;
        ckptr->set_upstream_addr = yume_socks5_client_tcp_set_upstream_addr;

        tiptr = &kptr->tcp;
        memcpy (tiptr, YUME_SOCKS5_TCP_TYPE, sizeof (YumeSocks5TCPIface));
    }

    return okptr;
}
