/*
 ============================================================================
 Name        : yume-task-cond.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Condition
 ============================================================================
 */

#include <stddef.h>

#include "kern/task/yume-task.h"
#include "lib/misc/yume-compiler.h"

#include "yume-task-cond.h"

struct _YumeTaskCondNode
{
    YumeTaskCondNode *prev;
    YumeTaskCondNode *next;

    YumeTask *task;
};

EXPORT_SYMBOL int
yume_task_cond_init (YumeTaskCond *self)
{
    self->waiters = NULL;

    return 0;
}

EXPORT_SYMBOL int
yume_task_cond_wait (YumeTaskCond *self, YumeTaskMutex *mutex)
{
    YumeTaskCondNode node;

    node.prev = NULL;
    node.next = self->waiters;
    node.task = yume_task_self ();

    if (self->waiters)
        self->waiters->prev = &node;
    self->waiters = &node;

    yume_task_mutex_unlock (mutex);
    do {
        yume_task_yield (YUME_TASK_WAITIO);
    } while (READ_ONCE (node.task));
    yume_task_mutex_lock (mutex);

    return 0;
}

EXPORT_SYMBOL int
yume_task_cond_timedwait (YumeTaskCond *self, YumeTaskMutex *mutex,
                         unsigned int milliseconds)
{
    YumeTaskCondNode node;

    node.prev = NULL;
    node.next = self->waiters;
    node.task = yume_task_self ();

    if (self->waiters)
        self->waiters->prev = &node;
    self->waiters = &node;

    yume_task_mutex_unlock (mutex);
    while (milliseconds && READ_ONCE (node.task))
        milliseconds = yume_task_sleep (milliseconds);
    yume_task_mutex_lock (mutex);

    if (READ_ONCE (node.task)) {
        if (node.prev)
            node.prev->next = node.next;
        if (node.next)
            node.next->prev = node.prev;
        if (self->waiters == &node)
            self->waiters = NULL;
        return -1;
    }

    return 0;
}

EXPORT_SYMBOL int
yume_task_cond_signal (YumeTaskCond *self)
{
    if (self->waiters) {
        yume_task_wakeup (self->waiters->task);

        self->waiters->task = NULL;
        self->waiters = self->waiters->next;
        if (self->waiters)
            self->waiters->prev = NULL;
    }

    return 0;
}

EXPORT_SYMBOL int
yume_task_cond_broadcast (YumeTaskCond *self)
{
    while (self->waiters) {
        yume_task_wakeup (self->waiters->task);

        self->waiters->task = NULL;
        self->waiters = self->waiters->next;
    }

    return 0;
}
