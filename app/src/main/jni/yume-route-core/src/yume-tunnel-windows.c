/*
 ============================================================================
 Name        : yume-tunnel-windows.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
 Description : Tunnel on Windows
 ============================================================================
 */

#if defined(__MSYS__)

#include <stdio.h>
#include <string.h>
#include <arpa/inet.h>

#include <yume-task.h>
#include <yume-task-io.h>
#include <yume-memory-allocator.h>

#include "yume-wintun.h"

#include "yume-tunnel.h"

typedef struct _YumePBuf YumePBuf;

struct _YumePBuf
{
    struct pbuf_custom base;
    void *mem;
};

static YumeWinTun *wintun;
static YumeWinTunAdapter *adapter;
static YumeWinTunSession *session;
static char tun_name[IFNAMSIZ];

static void
yume_pbuf_free (struct pbuf *p)
{
    YumePBuf *buf = (YumePBuf *)p;

    yume_wintun_session_release (session, buf->mem);
    yume_free (buf);
}

int
yume_tunnel_open (const char *name, int multi_queue)
{
    wintun = yume_wintun_open ();
    if (!wintun)
        return -1;

    adapter = yume_wintun_adapter_create (name);
    if (!adapter)
        goto free;

    strncpy (tun_name, name, IFNAMSIZ - 1);
    return 0;

free:
    yume_wintun_close (wintun);
    return -1;
}

void
yume_tunnel_close (int fd)
{
    if (session)
        yume_wintun_session_stop (session);
    yume_wintun_adapter_close (adapter);
    yume_wintun_close (wintun);
}

int
yume_tunnel_set_mtu (int mtu)
{
    return yume_wintun_adapter_set_mtu (adapter, mtu);
}

int
yume_tunnel_set_state (int state)
{
    if (state) {
        session = yume_wintun_session_start (adapter);
        if (!session)
            return -1;
    } else {
        if (session) {
            yume_wintun_session_stop (session);
            session = NULL;
        }
    }

    return 0;
}

int
yume_tunnel_set_ipv4 (const char *addr, unsigned int prefix)
{
    char ipv4[4];
    int res;

    res = inet_pton (AF_INET, addr, ipv4);
    if (!res)
        return -1;

    return yume_wintun_adapter_set_ipv4 (adapter, ipv4, prefix);
}

int
yume_tunnel_set_ipv6 (const char *addr, unsigned int prefix)
{
    char ipv6[16];
    int res;

    res = inet_pton (AF_INET6, addr, ipv6);
    if (!res)
        return -1;

    return yume_wintun_adapter_set_ipv6 (adapter, ipv6, prefix);
}

const char *
yume_tunnel_get_name (void)
{
    return tun_name;
}

const char *
yume_tunnel_get_index (void)
{
    static char tun_index[16];
    int index;

    index = yume_wintun_adapter_get_index (adapter);
    if (index < 0)
        return NULL;

    snprintf (tun_index, sizeof (tun_index) - 1, "%d", index);
    return tun_index;
}

int
yume_tunnel_add_task (int fd, YumeTask *task)
{
    void *handle = yume_wintun_session_get_read_wait_event (session);
    return yume_task_add_whandle (task, handle);
}

void
yume_tunnel_del_task (int fd, YumeTask *task)
{
    void *handle = yume_wintun_session_get_read_wait_event (session);
    yume_task_del_whandle (task, handle);
}

struct pbuf *
yume_tunnel_read (int fd, int mtu, YumeTaskIOYielder yielder, void *yielder_data)
{
    YumePBuf *buf;
    void *packet;
    int spin = 0;
    int size;

retry:
    packet = yume_wintun_session_receive (session, &size);
    if (!packet) {
        if (yume_wintun_get_last_error () == YUME_WINTUN_EAGAIN) {
            if (spin++ < 1000) {
                yume_task_yield (YUME_TASK_YIELD);
            } else if (yielder) {
                if (yielder (YUME_TASK_WAITIO, yielder_data))
                    return NULL;
            } else {
                yume_task_yield (YUME_TASK_WAITIO);
            }
            goto retry;
        } else {
            return NULL;
        }
    }

    buf = yume_malloc (sizeof (YumePBuf));
    if (!buf)
        return NULL;

    buf->mem = packet;
    buf->base.custom_free_function = yume_pbuf_free;
    pbuf_alloced_custom (PBUF_RAW, size, PBUF_RAM, &buf->base, packet, size);

    return &buf->base.pbuf;
}

ssize_t
yume_tunnel_write (int fd, struct pbuf *buf)
{
    struct pbuf *p = buf;
    void *packet;
    size_t size;

    packet = yume_wintun_session_allocate (session, p->tot_len);
    if (!packet)
        return -1;

    for (size = 0; p; p = p->next) {
        memcpy (packet + size, p->payload, p->len);
        size += p->len;
    }

    yume_wintun_session_send (session, packet);
    return size;
}

#endif /* __MSYS__ */
