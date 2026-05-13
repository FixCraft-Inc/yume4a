/*
 ============================================================================
 Name        : yume-task.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2025 everyone.
 Description :
 ============================================================================
 */

#include <stdlib.h>
#include <stdint.h>

#include "lib/misc/yume-compiler.h"
#include "kern/core/yume-task-system-private.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-private.h"

#include "yume-task.h"

#define YUME_TASK_STACK_SIZE (64 * 1024)

EXPORT_SYMBOL YumeTask *
yume_task_new (int stack_size)
{
    YumeTask *self;

    self = yume_malloc0 (sizeof (YumeTask));
    if (!self)
        return NULL;

    self->ref_count = 1;
    self->next_priority = YUME_TASK_PRIORITY_DEFAULT;

    if (stack_size < 0)
        stack_size = YUME_TASK_STACK_SIZE;

    self->stack = yume_task_stack_new (stack_size);
    if (!self->stack) {
        yume_free (self);
        return NULL;
    }

    self->stack_bottom = yume_task_stack_get_bottom (self->stack);
    self->sched_entity.task = self;

    yume_list_add_tail (&yume_task_system_get_context ()->all_tasks,
                       &self->list_node);

    return self;
}

EXPORT_SYMBOL YumeTask *
yume_task_ref (YumeTask *self)
{
    self->ref_count++;

    return self;
}

EXPORT_SYMBOL void
yume_task_unref (YumeTask *self)
{
    self->ref_count--;
    if (self->ref_count)
        return;

    yume_list_del (&yume_task_system_get_context ()->all_tasks, &self->list_node);

    yume_task_stack_destroy (self->stack);
    yume_free (self);
}

EXPORT_SYMBOL YumeTask *
yume_task_self (void)
{
    return yume_task_system_get_context ()->current_task;
}

EXPORT_SYMBOL YumeTaskState
yume_task_get_state (YumeTask *self)
{
    return self->state;
}

EXPORT_SYMBOL void
yume_task_set_priority (YumeTask *self, int priority)
{
    if (priority < YUME_TASK_PRIORITY_MIN)
        priority = YUME_TASK_PRIORITY_MIN;
    else if (priority > YUME_TASK_PRIORITY_MAX)
        priority = YUME_TASK_PRIORITY_MAX;

    self->next_priority = priority;
}

EXPORT_SYMBOL int
yume_task_get_priority (YumeTask *self)
{
    return self->next_priority;
}

EXPORT_SYMBOL int
yume_task_add_fd (YumeTask *self, int fd, unsigned int events)
{
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_fd_gen (
        revents, fd, YUME_TASK_IO_REACTOR_OP_ADD, events, &self->sched_entity);
    return yume_task_io_reactor_setup (reactor, revents, count);
}

EXPORT_SYMBOL int
yume_task_mod_fd (YumeTask *self, int fd, unsigned int events)
{
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_fd_gen (
        revents, fd, YUME_TASK_IO_REACTOR_OP_MOD, events, &self->sched_entity);
    return yume_task_io_reactor_setup (reactor, revents, count);
}

EXPORT_SYMBOL int
yume_task_del_fd (YumeTask *self, int fd)
{
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_fd_gen (
        revents, fd, YUME_TASK_IO_REACTOR_OP_DEL, 0, NULL);
    return yume_task_io_reactor_setup (reactor, revents, count);
}

EXPORT_SYMBOL int
yume_task_add_whandle (YumeTask *self, void *handle)
{
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_whandle_gen (
        revents, handle, YUME_TASK_IO_REACTOR_OP_ADD, &self->sched_entity);
    if (count < 0)
        return -1;
    return yume_task_io_reactor_setup (reactor, revents, count);
}

EXPORT_SYMBOL int
yume_task_del_whandle (YumeTask *self, void *handle)
{
    YumeTaskIOReactor *reactor;
    YumeTaskIOReactorSetupEvent revents[YUME_TASK_IO_REACTOR_EVENT_GEN_MAX];
    int count;

    reactor = yume_task_system_get_context ()->reactor;
    count = yume_task_io_reactor_setup_event_whandle_gen (
        revents, handle, YUME_TASK_IO_REACTOR_OP_DEL, NULL);
    if (count < 0)
        return -1;
    return yume_task_io_reactor_setup (reactor, revents, count);
}

EXPORT_SYMBOL void
yume_task_wakeup (YumeTask *task)
{
    yume_task_system_wakeup_task (task);
}

EXPORT_SYMBOL void
yume_task_yield (YumeTaskYieldType type)
{
    yume_task_system_schedule (type);
}

EXPORT_SYMBOL unsigned int
yume_task_sleep (unsigned int milliseconds)
{
    YumeTaskSystemContext *ctx;

    if (milliseconds == 0)
        return 0;

    ctx = yume_task_system_get_context ();
    return yume_task_timer_wait (ctx->timer, milliseconds, ctx->current_task);
}

EXPORT_SYMBOL void
yume_task_run (YumeTask *self, YumeTaskEntry entry, void *data)
{
    /* Skip to run task that already running */
    if (self->state != YUME_TASK_STOPPED)
        return;

    self->entry = entry;
    self->data = data;
    self->priority = self->next_priority;
    self->sched_key = self->next_priority;

    yume_task_system_run_new_task (self);
}

EXPORT_SYMBOL void
yume_task_exit (void)
{
    yume_task_system_kill_current_task ();
}

EXPORT_SYMBOL int
yume_task_join (YumeTask *task)
{
    if (task->joiner)
        return -1;

    task->joiner = yume_task_self ();

    for (;;) {
        if (yume_task_get_state (task) == YUME_TASK_STOPPED)
            break;

        yume_task_wakeup (task);
        yume_task_yield (YUME_TASK_WAITIO);
    }

    return 0;
}

EXPORT_SYMBOL void *
yume_task_get_data (YumeTask *self)
{
    return self->data;
}
