/*
 ============================================================================
 Name        : yume-task-io-reactor-kqueue.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : I/O Reactor KQueue
 ============================================================================
 */

#ifndef __YUME_TASK_IO_REACTOR_KQUEUE_H__
#define __YUME_TASK_IO_REACTOR_KQUEUE_H__

#include <poll.h>
#include <sys/types.h>
#include <sys/event.h>
#include <sys/time.h>

#ifndef EVFILT_EXCEPT
#define EVFILT_EXCEPT (0)
#endif

#define YUME_TASK_IO_REACTOR_EVENT_GEN_MAX (6)

typedef struct kevent YumeTaskIOReactorSetupEvent;
typedef struct kevent YumeTaskIOReactorWaitEvent;

enum _YumeTaskIOReactorEvents
{
    YUME_TASK_IO_REACTOR_EV_RO = EVFILT_READ,
    YUME_TASK_IO_REACTOR_EV_WO = EVFILT_WRITE,
    YUME_TASK_IO_REACTOR_EV_ER = EVFILT_EXCEPT,
};

enum _YumeTaskIOReactorOperation
{
    YUME_TASK_IO_REACTOR_OP_ADD,
    YUME_TASK_IO_REACTOR_OP_MOD,
    YUME_TASK_IO_REACTOR_OP_DEL,
};

static inline int
yume_task_io_reactor_wait (YumeTaskIOReactor *self,
                          YumeTaskIOReactorWaitEvent *events, int count,
                          int timeout)
{
    struct timespec *tsp = NULL;
    struct timespec ts;

    if (timeout >= 0) {
        ts.tv_sec = timeout / 1000;
        ts.tv_nsec = (timeout % 1000) * 1000000;
        tsp = &ts;
    }

    return kevent (self->fd, NULL, 0, events, count, tsp);
}

static inline void
yume_task_io_reactor_setup_event_set (YumeTaskIOReactorSetupEvent *event, int fd,
                                     YumeTaskIOReactorOperation op,
                                     unsigned int events, void *data)
{
    int action;

    switch (op) {
    case YUME_TASK_IO_REACTOR_OP_ADD:
    case YUME_TASK_IO_REACTOR_OP_MOD:
        action = EV_ADD;
        break;
    case YUME_TASK_IO_REACTOR_OP_DEL:
        action = EV_DELETE;
    }

    EV_SET (event, fd, events, action | EV_CLEAR, 0, 0, data);
}

static inline int
yume_task_io_reactor_setup_event_fd_gen (YumeTaskIOReactorSetupEvent *events,
                                        int fd, YumeTaskIOReactorOperation op,
                                        unsigned int poll_events, void *data)
{
    int count = 0;

    if (op > YUME_TASK_IO_REACTOR_OP_ADD) {
        if (!(poll_events & POLLIN))
            yume_task_io_reactor_setup_event_set (&events[count++], fd,
                                                 YUME_TASK_IO_REACTOR_OP_DEL,
                                                 YUME_TASK_IO_REACTOR_EV_RO,
                                                 data);
        if (!(poll_events & POLLOUT))
            yume_task_io_reactor_setup_event_set (&events[count++], fd,
                                                 YUME_TASK_IO_REACTOR_OP_DEL,
                                                 YUME_TASK_IO_REACTOR_EV_WO,
                                                 data);
        if (!(poll_events & POLLERR))
            yume_task_io_reactor_setup_event_set (&events[count++], fd,
                                                 YUME_TASK_IO_REACTOR_OP_DEL,
                                                 YUME_TASK_IO_REACTOR_EV_ER,
                                                 data);
        if (op == YUME_TASK_IO_REACTOR_OP_DEL)
            return count;
    }

    if (poll_events & POLLIN)
        yume_task_io_reactor_setup_event_set (&events[count++], fd, op,
                                             YUME_TASK_IO_REACTOR_EV_RO, data);
    if (poll_events & POLLOUT)
        yume_task_io_reactor_setup_event_set (&events[count++], fd, op,
                                             YUME_TASK_IO_REACTOR_EV_WO, data);
    if (poll_events & POLLERR)
        yume_task_io_reactor_setup_event_set (&events[count++], fd, op,
                                             YUME_TASK_IO_REACTOR_EV_ER, data);

    return count;
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
    switch (event->filter) {
    case YUME_TASK_IO_REACTOR_EV_RO:
        return POLLIN;
    case YUME_TASK_IO_REACTOR_EV_WO:
        return POLLOUT;
    default:
        return POLLERR;
    }
}

static inline void *
yume_task_io_reactor_wait_event_get_data (YumeTaskIOReactorWaitEvent *event)
{
    return event->udata;
}

#endif /* __YUME_TASK_IO_REACTOR_KQUEUE_H__ */
