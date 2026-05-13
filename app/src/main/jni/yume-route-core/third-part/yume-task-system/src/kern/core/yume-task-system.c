/*
 ============================================================================
 Name        : yume-task-system.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2025 everyone.
 Description :
 ============================================================================
 */

#include <stdlib.h>
#include <pthread.h>

#include "lib/misc/yume-compiler.h"
#include "lib/misc/yume-task-stack-detector.h"
#include "mem/api/yume-memory-allocator-api.h"
#include "mem/slice/yume-memory-allocator-slice.h"

#include "yume-task-system-private.h"

#include "yume-task-system.h"

static pthread_key_t key;
static pthread_once_t key_once = PTHREAD_ONCE_INIT;

static void
pthread_key_creator (void)
{
    pthread_key_create (&key, NULL);
}

YumeTaskSystemContext *
yume_task_system_get_context (void)
{
    return pthread_getspecific (key);
}

static inline int
yume_task_system_set_context (YumeTaskSystemContext *context)
{
    return pthread_setspecific (key, context);
}

EXPORT_SYMBOL int
yume_task_system_init (void)
{
    YumeMemoryAllocator *allocator = NULL;
    YumeTaskSystemContext *context;

#ifdef ENABLE_MEMALLOC_SLICE
    allocator = yume_memory_allocator_slice_new ();
#endif
    allocator = yume_memory_allocator_set_default (allocator);
    if (allocator)
        yume_memory_allocator_unref (allocator);

    pthread_once (&key_once, pthread_key_creator);

    if (yume_task_system_get_context ())
        goto exit;

    context = yume_malloc0 (sizeof (YumeTaskSystemContext));
    if (!context)
        goto exit;

    if (yume_task_system_set_context (context) < 0)
        goto free_context;

    context->reactor = yume_task_io_reactor_new ();
    if (!context->reactor)
        goto rest_context;

    context->timer = yume_task_timer_new (context);
    if (!context->timer)
        goto free_reactor;

    context->stack_detector = yume_task_stack_detector_new ();
    if (!context->stack_detector)
        goto free_timer;

    return 0;

free_timer:
    yume_task_timer_destroy (context->timer);
free_reactor:
    yume_task_io_reactor_destroy (context->reactor);
rest_context:
    yume_task_system_set_context (NULL);
free_context:
    yume_free (context);
exit:
    return -1;
}

EXPORT_SYMBOL void
yume_task_system_fini (void)
{
    YumeMemoryAllocator *allocator;
    YumeTaskSystemContext *context = yume_task_system_get_context ();

    if (context->dns_proxy)
        yume_task_dns_proxy_destroy (context->dns_proxy);
    yume_task_stack_detector_destroy (context->stack_detector);
    yume_task_timer_destroy (context->timer);
    yume_task_io_reactor_destroy (context->reactor);
    yume_free (context);
    yume_task_system_set_context (NULL);

    allocator = yume_memory_allocator_set_default (NULL);
    if (allocator)
        yume_memory_allocator_unref (allocator);
}

EXPORT_SYMBOL void
yume_task_system_run (void)
{
    yume_task_system_schedule (YUME_TASK_RUN_SCHEDULER);
}
