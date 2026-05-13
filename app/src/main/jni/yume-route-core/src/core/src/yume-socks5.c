/*
 ============================================================================
 Name        : yume-socks5.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2023 hev
 Description : Socks5
 ============================================================================
 */

#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <arpa/inet.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>
#include <yume-task-dns.h>
#include <yume-memory-allocator.h>

#include "yume-socks5-logger-priv.h"

#include "yume-socks5.h"

int
yume_socks5_get_timeout (YumeSocks5 *self)
{
    return self->timeout;
}

void
yume_socks5_set_timeout (YumeSocks5 *self, int timeout)
{
    self->timeout = timeout;
}

YumeSocks5AddrFamily
yume_socks5_get_addr_family (YumeSocks5 *self)
{
    return self->addr_family;
}

void
yume_socks5_set_addr_family (YumeSocks5 *self, YumeSocks5AddrFamily family)
{
    self->addr_family = family;
}

static int
yume_socks5_bind (YumeSocks5 *self, int sock, const struct sockaddr *dest)
{
    return 0;
}

int
yume_socks5_construct (YumeSocks5 *self, YumeSocks5Type type)
{
    int res;

    res = yume_object_construct (&self->base);
    if (res < 0)
        return res;

    LOG_D ("%p socks5 construct", self);

    YUME_OBJECT (self)->klass = YUME_SOCKS5_TYPE;

    self->fd = -1;
    self->timeout = -1;
    self->type = type;
    self->addr_family = YUME_SOCKS5_ADDR_FAMILY_UNSPEC;

    return 0;
}

static void
yume_socks5_destruct (YumeObject *base)
{
    YumeSocks5 *self = YUME_SOCKS5 (base);

    LOG_D ("%p socks5 destruct", self);

    if (self->fd >= 0) {
        yume_task_del_fd (yume_task_self (), self->fd);
        close (self->fd);
    }

    YUME_OBJECT_TYPE->destruct (base);
    yume_free (base);
}

YumeObjectClass *
yume_socks5_class (void)
{
    static YumeSocks5Class klass;
    YumeSocks5Class *kptr = &klass;
    YumeObjectClass *okptr = YUME_OBJECT_CLASS (kptr);

    if (!okptr->name) {
        memcpy (kptr, YUME_OBJECT_TYPE, sizeof (YumeObjectClass));

        okptr->name = "YumeSocks5";
        okptr->destruct = yume_socks5_destruct;

        kptr->binder = yume_socks5_bind;
    }

    return okptr;
}
