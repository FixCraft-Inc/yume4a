/*
 ============================================================================
 Name        : yume-memory-allocator-slice.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description : Memory allocator sliced
 ============================================================================
 */

#ifndef __YUME_MEMORY_ALLOCATOR_SLICE_H__
#define __YUME_MEMORY_ALLOCATOR_SLICE_H__

#include "mem/base/yume-memory-allocator.h"

typedef struct _YumeMemoryAllocatorSlice YumeMemoryAllocatorSlice;

YumeMemoryAllocator *yume_memory_allocator_slice_new (void);

#endif /* __YUME_MEMORY_ALLOCATOR_SLICE_H__ */
