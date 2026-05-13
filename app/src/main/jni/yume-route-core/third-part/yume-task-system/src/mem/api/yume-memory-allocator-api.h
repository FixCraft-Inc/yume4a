/*
 ============================================================================
 Name        : yume-memory-allocator-api.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Memory allocator APIs
 ============================================================================
 */

#ifndef __YUME_MEMORY_ALLOCATOR_API_H__
#define __YUME_MEMORY_ALLOCATOR_API_H__

#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * yume_malloc:
 * @size: bytes
 *
 * Allocate @size bytes memory from the default memory allocator.
 *
 * Returns: memory address
 *
 * Since: 1.0
 */
void *yume_malloc (size_t size);

/**
 * yume_malloc0:
 * @size: bytes
 *
 * Allocate @size bytes memory from the default memory allocator.
 * The memroy content will be cleared to zero.
 *
 * Returns: memory address
 *
 * Since: 1.0
 */
void *yume_malloc0 (size_t size);

/**
 * yume_calloc:
 * @nmemb: bytes
 * @size: bytes
 *
 * Allocate memory for an array of @nmemb elements of @size bytes each from the
 * default memory allocator. The memroy content will be cleared to zero.
 *
 * Returns: memory address
 *
 * Since: 5.1.1
 */
void *yume_calloc (size_t nmemb, size_t size);

/**
 * yume_realloc:
 * @ptr: memory address
 * @size: bytes
 *
 * Changes the size of the memory block pointed to by @ptr to @size bytes.
 *
 * Returns: memory address
 *
 * Since: 3.2
 */
void *yume_realloc (void *ptr, size_t size);

/**
 * yume_free:
 * @ptr: memory address
 *
 * Free the memory from memory allocator.
 *
 * Since: 1.0
 */
void yume_free (void *ptr);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_MEMORY_ALLOCATOR_API_H__ */
