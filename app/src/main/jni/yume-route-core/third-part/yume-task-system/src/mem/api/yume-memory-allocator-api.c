/*
 ============================================================================
 Name        : yume-memory-allocator-api.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Memory allocator APIs
 ============================================================================
 */

#include <string.h>

#include "lib/misc/yume-compiler.h"
#include "mem/base/yume-memory-allocator.h"

#include "yume-memory-allocator-api.h"

EXPORT_SYMBOL void *
yume_malloc (size_t size)
{
    YumeMemoryAllocator *allocator;

    allocator = yume_memory_allocator_default ();
    return yume_memory_allocator_alloc (allocator, size);
}

EXPORT_SYMBOL void *
yume_malloc0 (size_t size)
{
    YumeMemoryAllocator *allocator;
    void *data;

    allocator = yume_memory_allocator_default ();
    data = yume_memory_allocator_alloc (allocator, size);
    if (data)
        memset (data, 0, size);
    return data;
}

EXPORT_SYMBOL void *
yume_calloc (size_t nmemb, size_t size)
{
    YumeMemoryAllocator *allocator;
    void *data;

    if (!nmemb || !size)
        return NULL;

    size *= nmemb;
    allocator = yume_memory_allocator_default ();
    data = yume_memory_allocator_alloc (allocator, size);
    if (data)
        memset (data, 0, size);
    return data;
}

EXPORT_SYMBOL void *
yume_realloc (void *ptr, size_t size)
{
    YumeMemoryAllocator *allocator;

    allocator = yume_memory_allocator_default ();
    return yume_memory_allocator_realloc (allocator, ptr, size);
}

EXPORT_SYMBOL void
yume_free (void *ptr)
{
    YumeMemoryAllocator *allocator;

    allocator = yume_memory_allocator_default ();
    yume_memory_allocator_free (allocator, ptr);
}
