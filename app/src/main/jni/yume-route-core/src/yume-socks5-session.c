/*
 ============================================================================
 Name        : yume-socks5-session.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
               Modifications copyright (c) 2026 FixCraft Inc.
 License     : MIT (see ../LICENSE) -- significantly modified, see git history.
 Description : Yume SOCKS5 Session
 ============================================================================
 */

#include <string.h>
#include <sys/socket.h>

#include "yume-logger.h"
#include "yume-route-config.h"
#include "yume-socks5-client.h"

#include "yume-socks5-session.h"

void
yume_socks5_session_run_via_socks (YumeSocks5Session *self)
{
    YumeSocks5SessionIface *iface;
    YumeRouteConfigServer *srv;
    int res;

    LOG_D ("%p Yume SOCKS5 session run", self);

    srv = yume_route_config_get_socks5_server ();

    res = yume_socks5_client_connect (YUME_SOCKS5_CLIENT (self), srv->addr,
                                     srv->port);
    if (res < 0) {
        LOG_W ("%p Yume SOCKS5 session failed to connect to local SOCKS5 gateway %s:%d",
               self, srv->addr, srv->port);
        return;
    }

    if (srv->user && srv->pass) {
        yume_socks5_client_set_auth (YUME_SOCKS5_CLIENT (self), srv->user,
                                    srv->pass);
        LOG_D ("%p socks5 client auth %s:%s", self, srv->user, srv->pass);
    }

    res = yume_socks5_client_handshake (YUME_SOCKS5_CLIENT (self), srv->pipeline);
    if (res < 0) {
        LOG_W ("%p Yume SOCKS5 session failed local SOCKS5 handshake", self);
        return;
    }

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_SESSION_TYPE);
    iface->splicer (self);
}

void
yume_socks5_session_run (YumeSocks5Session *self)
{
    YumeSocks5SessionIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_SESSION_TYPE);
    if (iface->run) {
        iface->run (self);
        return;
    }

    yume_socks5_session_run_via_socks (self);
}

void
yume_socks5_session_terminate (YumeSocks5Session *self)
{
    YumeSocks5SessionIface *iface;
    int fd;

    LOG_D ("%p Yume SOCKS5 session terminate", self);

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_SESSION_TYPE);
    yume_socks5_set_timeout (YUME_SOCKS5 (self), 0);
    fd = YUME_SOCKS5 (self)->fd;
    if (fd >= 0)
        shutdown (fd, SHUT_RDWR);
    yume_task_wakeup (iface->get_task (self));
}

void
yume_socks5_session_set_task (YumeSocks5Session *self, YumeTask *task)
{
    YumeSocks5SessionIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_SESSION_TYPE);
    iface->set_task (self, task);
}

YumeListNode *
yume_socks5_session_get_node (YumeSocks5Session *self)
{
    YumeSocks5SessionIface *iface;

    iface = YUME_OBJECT_GET_IFACE (self, YUME_SOCKS5_SESSION_TYPE);
    return iface->get_node (self);
}

void *
yume_socks5_session_iface (void)
{
    static char type;

    return &type;
}
