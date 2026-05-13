/*
 ============================================================================
 Name        : yume-memory-allocator-slice.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description : Memory allocator sliced
 ============================================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lib/misc/yume-compiler.h"

#include "yume-memory-allocator-slice.h"

#define CACHED_SLICE_ALIGN CONFIG_MEMALLOC_SLICE_ALIGN
#define MAX_CACHED_SLICE_SIZE CONFIG_MEMALLOC_SLICE_MAX_SIZE
#define MAX_CACHED_SLICE_COUNT CONFIG_MEMALLOC_SLICE_MAX_COUNT
#define MAX_CACHED_SLICE_INDEX (MAX_CACHED_SLICE_SIZE / CACHED_SLICE_ALIGN)

typedef struct _YumeMemorySlice YumeMemorySlice;
typedef struct _YumeMemoryLRUNode YumeMemoryLRUNode;

struct _YumeMemorySlice
{
    YumeMemorySlice *next;
    int index;
};

struct _YumeMemoryLRUNode
{
    YumeMemoryLRUNode *prev;
    YumeMemoryLRUNode *next;
};

struct _YumeMemoryAllocatorSlice
{
    YumeMemoryAllocator base;

    YumeMemoryLRUNode *lru_head;
    YumeMemoryLRUNode *lru_tail;
    YumeMemoryLRUNode lru_nodes[MAX_CACHED_SLICE_INDEX];

    unsigned int cached_count;
    YumeMemorySlice *cached_mslices[MAX_CACHED_SLICE_INDEX];
};

static void *_yume_memory_allocator_alloc (YumeMemoryAllocator *self,
                                          size_t size);
static void *_yume_memory_allocator_realloc (YumeMemoryAllocator *self, void *ptr,
                                            size_t size);
static void _yume_memory_allocator_free (YumeMemoryAllocator *self, void *ptr);
static void _yume_memory_allocator_destroy (YumeMemoryAllocator *self);
static void _yume_memory_allocator_lru_insert (YumeMemoryAllocatorSlice *self,
                                              YumeMemoryLRUNode *node);
static void _yume_memory_allocator_lru_remove (YumeMemoryAllocatorSlice *self,
                                              YumeMemoryLRUNode *node);

YumeMemoryAllocator *
yume_memory_allocator_slice_new (void)
{
    YumeMemoryAllocator *allocator = NULL;
    YumeMemoryAllocatorSlice *self = NULL;

    allocator = malloc (sizeof (YumeMemoryAllocatorSlice));
    if (!allocator)
        return NULL;

    allocator->ref_count = 1;
    allocator->alloc = _yume_memory_allocator_alloc;
    allocator->realloc = _yume_memory_allocator_realloc;
    allocator->free = _yume_memory_allocator_free;
    allocator->destroy = _yume_memory_allocator_destroy;

    self = (YumeMemoryAllocatorSlice *)allocator;
    self->lru_head = NULL;
    self->lru_tail = NULL;
    self->cached_count = 0;
    memset (self->cached_mslices, 0, sizeof (self->cached_mslices));

    return allocator;
}

static void *
_yume_memory_allocator_alloc (YumeMemoryAllocator *allocator, size_t size)
{
    YumeMemoryAllocatorSlice *self = (YumeMemoryAllocatorSlice *)allocator;
    YumeMemorySlice *slice, **owner;
    int index;

    size = ALIGN_UP (size, CACHED_SLICE_ALIGN);
    index = size / CACHED_SLICE_ALIGN;

    switch (index) {
    case 0:
        return NULL;
    case 1 ... MAX_CACHED_SLICE_INDEX:
        owner = &self->cached_mslices[index - 1];
        break;
    default:
#ifdef _DEBUG
        printf ("[%p] default alloc size: %lu\n", self, size);
#endif
        slice = malloc (sizeof (YumeMemorySlice) + size);
        if (!slice)
            return NULL;
        slice->index = -1;
        return slice + 1;
    }

    if (*owner) {
        YumeMemoryLRUNode *node;

        slice = *owner;
        *owner = slice->next;
        self->cached_count--;

        node = &self->lru_nodes[index - 1];
        _yume_memory_allocator_lru_remove (self, node);
        if (*owner)
            _yume_memory_allocator_lru_insert (self, node);
    } else {
#ifdef _DEBUG
        printf ("[%p] alloc size: %lu\n", self, size);
#endif
        slice = malloc (sizeof (YumeMemorySlice) + size);
        if (!slice)
            return NULL;

        slice->index = index - 1;
    }

    return slice + 1;
}

static void *
_yume_memory_allocator_realloc (YumeMemoryAllocator *allocator, void *ptr,
                               size_t size)
{
    YumeMemorySlice *slice;
    int index;

    if (0 == size) {
        _yume_memory_allocator_free (allocator, ptr);
        return NULL;
    }

    slice = (YumeMemorySlice *)ptr - 1;
    size = ALIGN_UP (size, CACHED_SLICE_ALIGN);
    index = size / CACHED_SLICE_ALIGN;

    slice = realloc (slice, sizeof (YumeMemorySlice) + size);
    if (!slice)
        return NULL;

    switch (index) {
    case 1 ... MAX_CACHED_SLICE_INDEX:
        slice->index = index - 1;
#ifdef _DEBUG
        printf ("[%p] realloc size: %lu\n", allocator, size);
#endif
        break;
    default:
        slice->index = -1;
#ifdef _DEBUG
        printf ("[%p] default realloc size: %lu\n", allocator, size);
#endif
    }

    return slice + 1;
}

static void
_yume_memory_allocator_free (YumeMemoryAllocator *allocator, void *ptr)
{
    YumeMemoryAllocatorSlice *self = (YumeMemoryAllocatorSlice *)allocator;
    YumeMemorySlice *slice = (YumeMemorySlice *)ptr - 1;

    if (slice->index < 0) {
        free (slice);
        return;
    }

    if (self->cached_count >= MAX_CACHED_SLICE_COUNT) {
        YumeMemoryLRUNode *node = self->lru_tail;
        YumeMemorySlice **owner = &self->cached_mslices[node - self->lru_nodes];
        YumeMemorySlice *free_slice = *owner;

        *owner = free_slice->next;
        self->cached_count--;

        if (!*owner)
            _yume_memory_allocator_lru_remove (self, node);

        free (free_slice);
    }

    slice->next = self->cached_mslices[slice->index];
    self->cached_mslices[slice->index] = slice;
    self->cached_count++;

    if (!slice->next) {
        YumeMemoryLRUNode *node;

        node = &self->lru_nodes[slice->index];
        _yume_memory_allocator_lru_insert (self, node);
    }

#ifdef _DEBUG
    printf ("[%p] cached_count: %u\n", self, self->cached_count);
#endif
}

static void
_yume_memory_allocator_destroy (YumeMemoryAllocator *allocator)
{
    YumeMemoryAllocatorSlice *self = (YumeMemoryAllocatorSlice *)allocator;
    YumeMemoryLRUNode *node;

    for (node = self->lru_head; node; node = node->next) {
        YumeMemorySlice *iter = self->cached_mslices[node - self->lru_nodes];

        while (iter) {
            YumeMemorySlice *next = iter->next;
            free (iter);
            iter = next;
        }
    }
}

static void
_yume_memory_allocator_lru_insert (YumeMemoryAllocatorSlice *self,
                                  YumeMemoryLRUNode *node)
{
    node->prev = NULL;
    if (self->lru_head) {
        node->next = self->lru_head;
        self->lru_head->prev = node;
    } else {
        node->next = NULL;
    }
    self->lru_head = node;
    if (!self->lru_tail)
        self->lru_tail = node;
}

static void
_yume_memory_allocator_lru_remove (YumeMemoryAllocatorSlice *self,
                                  YumeMemoryLRUNode *node)
{
    if (node->prev)
        node->prev->next = node->next;
    if (node->next)
        node->next->prev = node->prev;
    if (self->lru_head == node)
        self->lru_head = node->next;
    if (self->lru_tail == node)
        self->lru_tail = node->prev;
}
