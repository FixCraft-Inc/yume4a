/*
 ============================================================================
 Name        : yume-task-mutex.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Mutex
 ============================================================================
 */

#include <stddef.h>

#include "kern/task/yume-task.h"
#include "lib/misc/yume-compiler.h"

#include "yume-task-mutex.h"

struct _YumeTaskMutexNode
{
    YumeTaskMutexNode *next;

    YumeTask *task;
};

EXPORT_SYMBOL int
yume_task_mutex_init (YumeTaskMutex *self)
{
    self->locker = 0;
    self->waiters = NULL;

    return 0;
}

EXPORT_SYMBOL int
yume_task_mutex_lock (YumeTaskMutex *self)
{
    if (self->locker) {
        YumeTaskMutexNode node;

        node.next = self->waiters;
        node.task = yume_task_self ();
        self->waiters = &node;

        do {
            yume_task_yield (YUME_TASK_WAITIO);
        } while (READ_ONCE (self->locker) ||
                 READ_ONCE (self->waiters) != &node);

        self->waiters = node.next;
    }

    barrier ();
    self->locker = 1;
    barrier ();

    return 0;
}

EXPORT_SYMBOL int
yume_task_mutex_trylock (YumeTaskMutex *self)
{
    if (self->locker)
        return -1;

    barrier ();
    self->locker = 1;
    barrier ();

    return 0;
}

EXPORT_SYMBOL int
yume_task_mutex_unlock (YumeTaskMutex *self)
{
    barrier ();
    self->locker = 0;
    barrier ();

    if (self->waiters)
        yume_task_wakeup (self->waiters->task);

    return 0;
}
