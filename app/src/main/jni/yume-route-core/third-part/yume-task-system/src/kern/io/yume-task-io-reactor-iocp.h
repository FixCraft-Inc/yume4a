/*
 ============================================================================
 Name        : yume-task-io-reactor-iocp.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2025 hev
 Description : I/O Reactor IOCP
 ============================================================================
 */

#ifndef __YUME_TASK_IO_REACTOR_IOCP_H__
#define __YUME_TASK_IO_REACTOR_IOCP_H__

#include <io.h>
#include <poll.h>
#include <pthread.h>

#include "lib/rbtree/yume-rbtree.h"
#include "lib/misc/yume-windows-api.h"

#define YUME_TASK_IO_REACTOR_EVENT_GEN_MAX (1)

typedef struct _YumeTaskIOReactorIOCP YumeTaskIOReactorIOCP;
typedef struct _YumeTaskIOReactorSetupEvent YumeTaskIOReactorSetupEvent;
typedef struct _YumeTaskIOReactorWaitEvent YumeTaskIOReactorWaitEvent;

struct _YumeTaskIOReactorIOCP
{
    YumeTaskIOReactor base;

    YumeRBTree tree;
    pthread_mutex_t mutex;
};

enum _YumeTaskIOReactorEvents
{
    YUME_TASK_IO_REACTOR_EV_RO = FD_READ | FD_ACCEPT,
    YUME_TASK_IO_REACTOR_EV_WO = FD_WRITE | FD_CONNECT,
    YUME_TASK_IO_REACTOR_EV_ER = FD_CLOSE,
};

enum _YumeTaskIOReactorOperation
{
    YUME_TASK_IO_REACTOR_OP_ADD,
    YUME_TASK_IO_REACTOR_OP_MOD,
    YUME_TASK_IO_REACTOR_OP_DEL,
};

struct _YumeTaskIOReactorSetupEvent
{
    YumeTaskIOReactorOperation op;

    long handle;
    int events;
    void *data;
};

struct _YumeTaskIOReactorWaitEvent
{
    int events;
    void *data;
};

int yume_task_io_reactor_wait (YumeTaskIOReactor *self,
                              YumeTaskIOReactorWaitEvent *events, int count,
                              int timeout);

static inline void
yume_task_io_reactor_setup_event_set (YumeTaskIOReactorSetupEvent *event,
                                     long handle, YumeTaskIOReactorOperation op,
                                     unsigned int events, void *data)
{
    event->op = op;
    event->handle = handle;
    event->events = events;
    event->data = data;
}

static inline int
yume_task_io_reactor_setup_event_fd_gen (YumeTaskIOReactorSetupEvent *events,
                                        long fd, YumeTaskIOReactorOperation op,
                                        unsigned int poll_events, void *data)
{
    YumeTaskIOReactorEvents reactor_events = 0;

    if (poll_events & POLLIN)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_RO;
    if (poll_events & POLLOUT)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_WO;
    if (poll_events & POLLERR)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_ER;

    yume_task_io_reactor_setup_event_set (events, get_osfhandle (fd), op,
                                         reactor_events, data);

    return 1;
}

static inline int
yume_task_io_reactor_setup_event_whandle_gen (YumeTaskIOReactorSetupEvent *events,
                                             void *handle,
                                             YumeTaskIOReactorOperation op,
                                             void *data)
{
    yume_task_io_reactor_setup_event_set (events, (long)handle, op, 0, data);

    return 1;
}

static inline unsigned int
yume_task_io_reactor_wait_event_get_events (YumeTaskIOReactorWaitEvent *event)
{
    unsigned int events = 0;

    if (event->events & YUME_TASK_IO_REACTOR_EV_RO)
        events |= POLLIN;
    if (event->events & YUME_TASK_IO_REACTOR_EV_WO)
        events |= POLLOUT;
    if (event->events & YUME_TASK_IO_REACTOR_EV_ER)
        events |= POLLERR;

    return events;
}

static inline void *
yume_task_io_reactor_wait_event_get_data (YumeTaskIOReactorWaitEvent *event)
{
    return event->data;
}

#endif /* __YUME_TASK_IO_REACTOR_IOCP_H__ */
