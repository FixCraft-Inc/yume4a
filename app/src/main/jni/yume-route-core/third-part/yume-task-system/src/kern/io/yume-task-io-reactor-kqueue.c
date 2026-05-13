/*
 ============================================================================
 Name        : yume-task-io-reactor-kqueue.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : I/O Reactor KQueue
 ============================================================================
 */

#if !defined(__linux__) && !defined(__MSYS__)

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

    self->fd = kqueue ();
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
    int i, res = -1;

    for (i = 0; i < count; i++) {
        if (!(events[i].flags & EV_DELETE)) {
            res = kevent (self->fd, &events[i], count - i, NULL, 0, NULL);
            break;
        }
        res &= kevent (self->fd, &events[i], 1, NULL, 0, NULL);
    }

    return res;
}

#endif /* !defined(__linux__) && !defined(__MSYS__) */
