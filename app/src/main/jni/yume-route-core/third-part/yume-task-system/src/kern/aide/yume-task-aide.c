/*
 ============================================================================
 Name        : yume-task-aide.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2022 - 2024 everyone.
 Description : Aide
 ============================================================================
 */

#include <poll.h>
#include <errno.h>
#include <unistd.h>
#include <pthread.h>

#include "kern/task/yume-task-private.h"
#include "kern/io/yume-task-io-reactor.h"
#include "lib/io/basic/yume-task-io.h"

#include "yume-task-aide.h"

static pthread_t thread;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static YumeTaskIOReactor *reactor;

static void *
yume_task_aide_entry (void *data)
{
    for (;;) {
        YumeTaskIOReactorWaitEvent events[256];
        int i, count;

        count = yume_task_io_reactor_wait (reactor, events, 256, -1);

        for (i = 0; i < count; i++) {
            YumeTaskAideWork *work;
            unsigned int revents;

            work = yume_task_io_reactor_wait_event_get_data (&events[i]);
            revents = yume_task_io_reactor_wait_event_get_events (&events[i]);

            work->handler (revents, work->data);
        }
    }

    return NULL;
}

int
yume_task_aide_init (void)
{
    int res = 0;

    if (reactor)
        return 0;

    pthread_mutex_lock (&mutex);
    if (!reactor) {
        reactor = yume_task_io_reactor_new ();
        if (!reactor) {
            res = -1;
        } else {
            res = pthread_create (&thread, NULL, yume_task_aide_entry, NULL);
            if (res != 0)
                res = -1;
        }
    }
    pthread_mutex_unlock (&mutex);

    return res;
}

int
yume_task_aide_add (YumeTaskAideWork *work)
{
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    count = yume_task_io_reactor_setup_event_fd_gen (
        revents, work->fd, YUME_TASK_IO_REACTOR_OP_ADD, work->events, work);

    return yume_task_io_reactor_setup (reactor, revents, count);
}

int
yume_task_aide_del (YumeTaskAideWork *work)
{
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    count = yume_task_io_reactor_setup_event_fd_gen (
        revents, work->fd, YUME_TASK_IO_REACTOR_OP_DEL, 0, NULL);

    return yume_task_io_reactor_setup (reactor, revents, count);
}
