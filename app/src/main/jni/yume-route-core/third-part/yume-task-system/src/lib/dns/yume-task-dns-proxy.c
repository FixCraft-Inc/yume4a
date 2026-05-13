/*
 ============================================================================
 Name        : yume-task-dns-proxy.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2022 everyone.
 Description : DNS Proxy
 ============================================================================
 */

#include <poll.h>
#include <errno.h>
#include <unistd.h>

#include "kern/aide/yume-task-aide.h"
#include "kern/sync/yume-task-mutex.h"
#include "kern/core/yume-task-system-private.h"
#include "lib/io/basic/yume-task-io.h"
#include "lib/io/socket/yume-task-io-socket.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-dns-proxy.h"

typedef enum _YumeTaskDNSCallType YumeTaskDNSCallType;
typedef struct _YumeTaskDNSCall YumeTaskDNSCall;
typedef struct _YumeTaskDNSCallGetAddrInfo YumeTaskDNSCallGetAddrInfo;
typedef struct _YumeTaskDNSCallGetNameInfo YumeTaskDNSCallGetNameInfo;

enum _YumeTaskDNSCallType
{
    YUME_TASK_DNS_CALL_GETADDRINFO,
    YUME_TASK_DNS_CALL_GETNAMEINFO,
};

struct _YumeTaskDNSProxy
{
    int fd;
    YumeTaskMutex mutex;
    YumeTaskAideWork work;
    YumeTaskDNSCall *call;
    YumeTaskSchedEntity sched_entity;
};

struct _YumeTaskDNSCall
{
    int type;
};

struct _YumeTaskDNSCallGetAddrInfo
{
    YumeTaskDNSCall base;
    const char *node;
    const char *service;
    const struct addrinfo *hints;
    struct addrinfo **res;
    int err;
    int ret;
};

struct _YumeTaskDNSCallGetNameInfo
{
    YumeTaskDNSCall base;
    const struct sockaddr *addr;
    char *node;
    char *service;
    socklen_t addrlen;
    socklen_t nodelen;
    socklen_t servicelen;
    int flags;
    int err;
    int ret;
};

static YumeTask dummy_task = { .state = YUME_TASK_RUNNING };

static void
yume_task_dns_server_getaddrinfo (YumeTaskDNSCall *call)
{
    YumeTaskDNSCallGetAddrInfo *gai = (YumeTaskDNSCallGetAddrInfo *)call;

    gai->ret = getaddrinfo (gai->node, gai->service, gai->hints, gai->res);
    gai->err = errno;
}

static void
yume_task_dns_server_getnameinfo (YumeTaskDNSCall *call)
{
    YumeTaskDNSCallGetNameInfo *gni = (YumeTaskDNSCallGetNameInfo *)call;

    gni->ret = getnameinfo (gni->addr, gni->addrlen, gni->node, gni->nodelen,
                            gni->service, gni->servicelen, gni->flags);
    gni->err = errno;
}

static void
yume_task_dns_server_handler (unsigned int revents, void *data)
{
    YumeTaskDNSProxy *proxy = data;

    for (;;) {
        char sync = 's';
        int res;

        res = read (proxy->work.fd, &sync, sizeof (sync));
        if (0 >= res)
            break;

        switch (proxy->call->type) {
        case YUME_TASK_DNS_CALL_GETADDRINFO:
            yume_task_dns_server_getaddrinfo (proxy->call);
            break;
        case YUME_TASK_DNS_CALL_GETNAMEINFO:
            yume_task_dns_server_getnameinfo (proxy->call);
            break;
        }

        for (;;) {
            struct pollfd pfd;

            res = write (proxy->work.fd, &sync, sizeof (sync));
            if ((0 <= res) || (EAGAIN != errno))
                break;

            pfd.fd = proxy->work.fd;
            pfd.events = POLLOUT;
            poll (&pfd, 1, -1);
        }
    }
}

YumeTaskDNSProxy *
yume_task_dns_proxy_new (void)
{
    YumeTaskDNSProxy *self;
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int fds[2];
    int count;
    int res;

    res = yume_task_aide_init ();
    if (0 > res)
        return NULL;

    self = yume_malloc0 (sizeof (YumeTaskDNSProxy));
    if (!self)
        goto exit;

    res = yume_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, fds);
    if (0 > res)
        goto free;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_fd_gen (revents, fds[0],
                                                    YUME_TASK_IO_REACTOR_OP_ADD,
                                                    POLLIN | POLLOUT,
                                                    &self->sched_entity);
    res = yume_task_io_reactor_setup (reactor, revents, count);
    if (0 > res)
        goto close;

    self->fd = fds[0];
    self->work.fd = fds[1];
    self->work.events = POLLIN;
    self->work.handler = yume_task_dns_server_handler;
    self->work.data = self;
    res = yume_task_aide_add (&self->work);
    if (0 > res)
        goto close;

    return self;

close:
    close (fds[0]);
    close (fds[1]);
free:
    yume_free (self);
exit:
    return NULL;
}

void
yume_task_dns_proxy_destroy (YumeTaskDNSProxy *self)
{
    yume_task_aide_del (&self->work);
    close (self->work.fd);
    close (self->fd);
    yume_free (self);
}

static void
yume_task_dns_proxy_call (YumeTaskDNSProxy *self, YumeTaskDNSCall *call)
{
    yume_task_mutex_lock (&self->mutex);

    self->call = call;
    self->sched_entity.task = yume_task_self ();

    for (;;) {
        char sync = 's';
        int res = write (self->fd, &sync, sizeof (sync));
        if ((0 <= res) || (EAGAIN != errno))
            break;

        yume_task_yield (YUME_TASK_WAITIO);
    }

    for (;;) {
        char sync;
        int res = read (self->fd, &sync, sizeof (sync));
        if ((0 <= res) || (EAGAIN != errno))
            break;

        yume_task_yield (YUME_TASK_WAITIO);
    }

    self->sched_entity.task = &dummy_task;

    yume_task_mutex_unlock (&self->mutex);
}

int
yume_task_dns_proxy_getaddrinfo (YumeTaskDNSProxy *self, const char *node,
                                const char *service,
                                const struct addrinfo *hints,
                                struct addrinfo **res)
{
    YumeTaskDNSCallGetAddrInfo gai;

    gai.base.type = YUME_TASK_DNS_CALL_GETADDRINFO;
    gai.node = node;
    gai.service = service;
    gai.hints = hints;
    gai.res = res;
    gai.ret = -1;

    yume_task_dns_proxy_call (self, &gai.base);

    errno = gai.err;
    return gai.ret;
}

int
yume_task_dns_proxy_getnameinfo (YumeTaskDNSProxy *self,
                                const struct sockaddr *addr, socklen_t addrlen,
                                char *node, socklen_t nodelen, char *service,
                                socklen_t servicelen, int flags)
{
    YumeTaskDNSCallGetNameInfo gni;

    gni.base.type = YUME_TASK_DNS_CALL_GETNAMEINFO;
    gni.addr = addr;
    gni.addrlen = addrlen;
    gni.node = node;
    gni.nodelen = nodelen;
    gni.service = service;
    gni.servicelen = servicelen;
    gni.flags = flags;
    gni.ret = -1;

    yume_task_dns_proxy_call (self, &gni.base);

    errno = gni.err;
    return gni.ret;
}
