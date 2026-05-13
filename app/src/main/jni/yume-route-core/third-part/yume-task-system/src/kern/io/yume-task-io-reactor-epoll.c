/*
 ============================================================================
 Name        : yume-task-io-reactor-epoll.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : I/O Reactor EPoll
 ============================================================================
 */

#if defined(__linux__)

#include <fcntl.h>
#include <unistd.h>

#include "mem/api/yume-memory-allocator-api.h"

#include "kern/io/yume-task-io-reactor.h"

YumeTaskIOReactor *
yume_task_io_reactor_new (void)
{
    YumeTaskIOReactor *self;
    int flags;

    self = yume_malloc0 (sizeof (YumeTaskIOReactor));
    if (!self)
        return NULL;

    self->fd = epoll_create (128);
    if (self->fd < 0) {
        yume_free (self);
        return NULL;
    }

    flags = fcntl (self->fd, F_GETFD);
    if (flags < 0) {
        yume_free (self);
        return NULL;
    }

    flags |= FD_CLOEXEC;
    if (fcntl (self->fd, F_SETFD, flags) < 0) {
        yume_free (self);
        return NULL;
    }

    return self;
}

void
yume_task_io_reactor_destroy (YumeTaskIOReactor *self)
{
    close (self->fd);
    yume_free (self);
}

int
yume_task_io_reactor_setup (YumeTaskIOReactor *self,
                           YumeTaskIOReactorSetupEvent *events, int count)
{
    int i, res = 0;

    for (i = 0; i < count; i++) {
        YumeTaskIOReactorSetupEvent *ev = &events[i];
        res |= epoll_ctl (self->fd, ev->op, ev->fd, &ev->event);
    }

    return res;
}

#endif /* defined(__linux__) */
