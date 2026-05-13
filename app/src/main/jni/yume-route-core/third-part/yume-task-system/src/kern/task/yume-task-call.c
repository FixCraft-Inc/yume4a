/*
 ============================================================================
 Name        : yume-task-call.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description : Task call
 ============================================================================
 */

#include <stdint.h>

#include "lib/misc/yume-compiler.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-call.h"

extern void yume_task_execute (YumeTaskCall *self, YumeTaskCallEntry entry);

EXPORT_SYMBOL YumeTaskCall *
yume_task_call_new (int base_size, int stack_size)
{
    YumeTaskCall *self;

    self = yume_malloc (base_size + stack_size);
    if (self) {
        uintptr_t stack_addr;
        stack_addr = (uintptr_t)((void *)self + base_size + stack_size);
        self->stack_top = (void *)ALIGN_DOWN (stack_addr, 16);
    }

    return self;
}

EXPORT_SYMBOL void
yume_task_call_destroy (YumeTaskCall *self)
{
    yume_free (self);
}

EXPORT_SYMBOL void *
yume_task_call_jump (YumeTaskCall *self, YumeTaskCallEntry entry)
{
    yume_task_execute (self, entry);
    return self->retval;
}
