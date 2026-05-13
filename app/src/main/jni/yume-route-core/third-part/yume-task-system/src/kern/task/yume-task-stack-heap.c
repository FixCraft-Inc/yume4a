/*
 ============================================================================
 Name        : yume-task-stack-heap.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description :
 ============================================================================
 */

#include <assert.h>
#include <stdint.h>

#include "lib/misc/yume-compiler.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-stack.h"

#if CONFIG_STACK_BACKEND == STACK_HEAP

#ifdef ENABLE_STACK_OVERFLOW_DETECTION
#if CONFIG_STACK_OVERFLOW_DETECTION == 1
#define STACK_OVERFLOW_DETECTION_SIZE (4)
#elif CONFIG_STACK_OVERFLOW_DETECTION == 2
#define STACK_OVERFLOW_DETECTION_SIZE (4096)
#else
#define STACK_OVERFLOW_DETECTION_SIZE (8192)
#endif
#define STACK_OVERFLOW_DETECTION_TAG (0xdeadbeefu)
#else
#define STACK_OVERFLOW_DETECTION_SIZE (0)
#endif

struct _YumeTaskStack
{
    int size;
};

static inline void
yume_task_stack_detection_mark (YumeTaskStack *self)
{
#ifdef ENABLE_STACK_OVERFLOW_DETECTION
    const unsigned int tag = STACK_OVERFLOW_DETECTION_TAG;
    const unsigned int tags = STACK_OVERFLOW_DETECTION_SIZE / sizeof (tag);
    unsigned int *tagp = (unsigned int *)(self + 1);
    int i;

    for (i = 0; i < tags; i++)
        tagp[i] = tag;
#endif
}

static inline void
yume_task_stack_detection_check (YumeTaskStack *self)
{
#ifdef ENABLE_STACK_OVERFLOW_DETECTION
    const unsigned int tag = STACK_OVERFLOW_DETECTION_TAG;
    const unsigned int tags = STACK_OVERFLOW_DETECTION_SIZE / sizeof (tag);
    unsigned int *tagp = (unsigned int *)(self + 1);
    int i;

    for (i = 0; i < tags; i++)
        assert (tagp[i] == tag);
#endif
}

YumeTaskStack *
yume_task_stack_new (int size)
{
    YumeTaskStack *self;

    size += STACK_OVERFLOW_DETECTION_SIZE;
    self = yume_malloc (sizeof (YumeTaskStack) + size);
    if (self)
        self->size = size;

    yume_task_stack_detection_mark (self);
    return self;
}

void
yume_task_stack_destroy (YumeTaskStack *self)
{
    yume_task_stack_detection_check (self);
    yume_free (self);
}

void *
yume_task_stack_get_base (YumeTaskStack *self)
{
    return (void *)(self + 1);
}

void *
yume_task_stack_get_bottom (YumeTaskStack *self)
{
    return (void *)ALIGN_DOWN ((intptr_t)self + self->size, 16);
}

#endif /* CONFIG_STACK_BACKEND == STACK_HEAP */
