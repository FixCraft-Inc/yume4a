/*
 ============================================================================
 Name        : yume-memory-allocator-simple.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Memory allocator simple
 ============================================================================
 */

#ifndef __YUME_MEMORY_ALLOCATOR_SIMPLE_H__
#define __YUME_MEMORY_ALLOCATOR_SIMPLE_H__

#include "mem/base/yume-memory-allocator.h"

/**
 * yume_memory_allocator_simple_new:
 *
 * Creates a new simple memory allocator.
 *
 * Returns: a new #YumeMemoryAllocator
 *
 * Since: 3.2
 */
YumeMemoryAllocator *yume_memory_allocator_simple_new (void);

#endif /* __YUME_MEMORY_ALLOCATOR_SIMPLE_H__ */
