/*
 ============================================================================
 Name        : yume-memory-allocator.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description : Memory allocator
 ============================================================================
 */

#ifndef __YUME_MEMORY_ALLOCATOR_H__
#define __YUME_MEMORY_ALLOCATOR_H__

#include <stddef.h>

typedef struct _YumeMemoryAllocator YumeMemoryAllocator;

typedef void *(*YumeMemoryAllocatorAlloc) (YumeMemoryAllocator *self,
                                          size_t size);
typedef void *(*YumeMemoryAllocatorRealloc) (YumeMemoryAllocator *self, void *ptr,
                                            size_t size);
typedef void (*YumeMemoryAllocatorFree) (YumeMemoryAllocator *self, void *ptr);
typedef void (*YumeMemoryAllocatorDestroy) (YumeMemoryAllocator *self);

struct _YumeMemoryAllocator
{
    YumeMemoryAllocatorAlloc alloc;
    YumeMemoryAllocatorRealloc realloc;
    YumeMemoryAllocatorFree free;
    YumeMemoryAllocatorDestroy destroy;

    unsigned int ref_count;
};

/**
 * yume_memory_allocator_default:
 *
 * Get the default memory allocator.
 *
 * Returns: a #YumeMemoryAllocator
 *
 * Since: 1.0
 */
YumeMemoryAllocator *yume_memory_allocator_default (void);

/**
 * yume_memory_allocator_set_default:
 * @allocator: a #YumeMemoryAllocator
 *
 * Set the default memory allocator.
 *
 * Returns: The old #YumeMemoryAllocator
 *
 * Since: 1.0
 */
YumeMemoryAllocator *
yume_memory_allocator_set_default (YumeMemoryAllocator *allocator);

/**
 * yume_memory_allocator_ref:
 * @self: a #YumeMemoryAllocator
 *
 * Increases the reference count of the @self by one.
 *
 * Returns: a #YumeMemoryAllocator
 *
 * Since: 1.0
 */
YumeMemoryAllocator *yume_memory_allocator_ref (YumeMemoryAllocator *self);

/**
 * yume_memory_allocator_unref:
 * @self: a #YumeMemoryAllocator
 *
 * Decreases the reference count of @self. When its reference count
 * drops to 0, the object is finalized (i.e. its memory is freed).
 *
 * Since: 1.0
 */
void yume_memory_allocator_unref (YumeMemoryAllocator *self);

/**
 * yume_memory_allocator_alloc:
 * @self: a #YumeMemoryAllocator
 * @size: bytes
 *
 * Allocate @size bytes memory from the default memory allocator.
 *
 * Returns: memory address
 *
 * Since: 1.0
 */
void *yume_memory_allocator_alloc (YumeMemoryAllocator *self, size_t size);

/**
 * yume_memory_allocator_realloc:
 * @self: a #YumeMemoryAllocator
 * @ptr: memory address
 * @size: bytes
 *
 * Changes the size of the memory block pointed to by @ptr to @size bytes.
 *
 * Returns: memory address
 *
 * Since: 3.2
 */
void *yume_memory_allocator_realloc (YumeMemoryAllocator *self, void *ptr,
                                    size_t size);

/**
 * yume_memory_allocator_free:
 * @self: a #YumeMemoryAllocator
 * @ptr: memory address
 *
 * Free the memory from memory allocator.
 *
 * Since: 1.0
 */
void yume_memory_allocator_free (YumeMemoryAllocator *self, void *ptr);

#endif /* __YUME_MEMORY_ALLOCATOR_H__ */
