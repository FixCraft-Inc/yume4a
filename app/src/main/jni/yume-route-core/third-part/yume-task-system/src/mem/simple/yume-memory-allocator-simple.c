/*
 ============================================================================
 Name        : yume-memory-allocator-simple.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Memory allocator simple
 ============================================================================
 */

#include <stdlib.h>

#include "yume-memory-allocator-simple.h"

static void *_yume_memory_allocator_alloc (YumeMemoryAllocator *self,
                                          size_t size);
static void *_yume_memory_allocator_realloc (YumeMemoryAllocator *self, void *ptr,
                                            size_t size);
static void _yume_memory_allocator_free (YumeMemoryAllocator *self, void *ptr);

YumeMemoryAllocator *
yume_memory_allocator_simple_new (void)
{
    YumeMemoryAllocator *self = NULL;

    self = malloc (sizeof (YumeMemoryAllocator));
    if (!self)
        return NULL;

    self->ref_count = 1;
    self->alloc = _yume_memory_allocator_alloc;
    self->realloc = _yume_memory_allocator_realloc;
    self->free = _yume_memory_allocator_free;
    self->destroy = NULL;

    return self;
}

static void *
_yume_memory_allocator_alloc (YumeMemoryAllocator *self, size_t size)
{
    return malloc (size);
}

static void *
_yume_memory_allocator_realloc (YumeMemoryAllocator *self, void *ptr, size_t size)
{
    return realloc (ptr, size);
}

static void
_yume_memory_allocator_free (YumeMemoryAllocator *self, void *ptr)
{
    free (ptr);
}
