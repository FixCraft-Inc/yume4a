/*
 ============================================================================
 Name        : yume-task-io-reactor-epoll.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2022 everyone.
 Description : I/O Reactor EPoll
 ============================================================================
 */

#ifndef __YUME_TASK_IO_REACTOR_EPOLL_H__
#define __YUME_TASK_IO_REACTOR_EPOLL_H__

#include <poll.h>
#include <sys/epoll.h>

#define YUME_TASK_IO_REACTOR_EVENT_GEN_MAX (1)

typedef struct _YumeTaskIOReactorSetupEvent YumeTaskIOReactorSetupEvent;
typedef struct epoll_event YumeTaskIOReactorWaitEvent;

enum _YumeTaskIOReactorEvents
{
    YUME_TASK_IO_REACTOR_EV_RO = EPOLLIN,
    YUME_TASK_IO_REACTOR_EV_WO = EPOLLOUT,
    YUME_TASK_IO_REACTOR_EV_ER = EPOLLERR,
};

enum _YumeTaskIOReactorOperation
{
    YUME_TASK_IO_REACTOR_OP_ADD = EPOLL_CTL_ADD,
    YUME_TASK_IO_REACTOR_OP_MOD = EPOLL_CTL_MOD,
    YUME_TASK_IO_REACTOR_OP_DEL = EPOLL_CTL_DEL,
};

struct _YumeTaskIOReactorSetupEvent
{
    YumeTaskIOReactorOperation op;

    int fd;
    struct epoll_event event;
};

static inline int
yume_task_io_reactor_wait (YumeTaskIOReactor *self,
                          YumeTaskIOReactorWaitEvent *events, int count,
                          int timeout)
{
    return epoll_wait (self->fd, events, count, timeout);
}

static inline void
yume_task_io_reactor_setup_event_set (YumeTaskIOReactorSetupEvent *event, int fd,
                                     YumeTaskIOReactorOperation op,
                                     unsigned int events, void *data)
{
    event->op = op;
    event->fd = fd;
    event->event.events = events | EPOLLET;
    event->event.data.ptr = data;
}

static inline int
yume_task_io_reactor_setup_event_fd_gen (YumeTaskIOReactorSetupEvent *events,
                                        int fd, YumeTaskIOReactorOperation op,
                                        unsigned int poll_events, void *data)
{
    YumeTaskIOReactorEvents reactor_events = 0;

    if (poll_events & POLLIN)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_RO;
    if (poll_events & POLLOUT)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_WO;
    if (poll_events & POLLERR)
        reactor_events |= YUME_TASK_IO_REACTOR_EV_ER;

    yume_task_io_reactor_setup_event_set (events, fd, op, reactor_events, data);

    return 1;
}

static inline int
yume_task_io_reactor_setup_event_whandle_gen (YumeTaskIOReactorSetupEvent *events,
                                             void *handle,
                                             YumeTaskIOReactorOperation op,
                                             void *data)
{
    return -1;
}

static inline unsigned int
yume_task_io_reactor_wait_event_get_events (YumeTaskIOReactorWaitEvent *event)
{
    return event->events;
}

static inline void *
yume_task_io_reactor_wait_event_get_data (YumeTaskIOReactorWaitEvent *event)
{
    return event->data.ptr;
}

#endif /* __YUME_TASK_IO_REACTOR_EPOLL_H__ */
