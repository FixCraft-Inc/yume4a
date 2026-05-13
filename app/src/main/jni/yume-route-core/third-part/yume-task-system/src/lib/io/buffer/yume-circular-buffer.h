/*
 ============================================================================
 Name        : yume-circular-buffer.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Circular buffer
 ============================================================================
 */

#ifndef __YUME_CIRCULAR_BUFFER_H__
#define __YUME_CIRCULAR_BUFFER_H__

#include <sys/uio.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeCircularBuffer YumeCircularBuffer;

/**
 * yume_circular_buffer_new:
 * @max_size: max buffer size
 *
 * Creates a new circular buffer. The max buffer size is specified by @max_size.
 *
 * Returns: a new #YumeCircularBuffer.
 *
 * Since: 4.6.1
 */
YumeCircularBuffer *yume_circular_buffer_new (size_t max_size);

/**
 * yume_circular_buffer_ref:
 * @self: a #YumeCircularBuffer
 *
 * Increases the reference count of the @self by one.
 *
 * Returns: a #YumeCircularBuffer
 *
 * Since: 4.6.1
 */
YumeCircularBuffer *yume_circular_buffer_ref (YumeCircularBuffer *self);

/**
 * yume_circular_buffer_unref:
 * @self: a #YumeCircularBuffer
 *
 * Decreases the reference count of @self. When its reference count
 * drops to 0, the object is finalized (i.e. its memory is freed).
 *
 * Since: 4.6.1
 */
void yume_circular_buffer_unref (YumeCircularBuffer *self);

/**
 * yume_circular_buffer_get_max_size:
 * @self: a #YumeCircularBuffer
 *
 * Get the max size of buffer.
 *
 * Returns: the number of bytes actually allocated
 *
 * Since: 4.6.1
 */
size_t yume_circular_buffer_get_max_size (YumeCircularBuffer *self);

/**
 * yume_circular_buffer_get_use_size:
 * @self: a #YumeCircularBuffer
 *
 * Get the use size of buffer.
 *
 * Returns: the number of bytes actually used
 *
 * Since: 4.6.1
 */
size_t yume_circular_buffer_get_use_size (YumeCircularBuffer *self);

/**
 * yume_circular_buffer_reading:
 * @self: a #YumeCircularBuffer
 * @iov: a io vector contain two elements
 *
 * The reading function start a buffer reading and update the @iov to point to
 * data in buffer.
 *
 * Returns: the number of iov actually used
 *
 * Since: 4.6.1
 */
int yume_circular_buffer_reading (YumeCircularBuffer *self, struct iovec *iov);

/**
 * yume_circular_buffer_read_finish:
 * @self: a #YumeCircularBuffer
 * @size: the number of bytes actually read
 *
 * the read_finish function stop buffer reading and increase read pointer by
 * @size.
 *
 * returns: the number of iov actually used
 *
 * since: 4.6.1
 */
void yume_circular_buffer_read_finish (YumeCircularBuffer *self, size_t size);

/**
 * yume_circular_buffer_writing:
 * @self: a #YumeCircularBuffer
 * @iov: a io vector contain two elements
 *
 * The writing function start a buffer writing and update the @iov to point to
 * data in buffer.
 *
 * Returns: the number of iov actually used
 *
 * Since: 4.6.1
 */
int yume_circular_buffer_writing (YumeCircularBuffer *self, struct iovec *iov);

/**
 * yume_circular_buffer_write_finish:
 * @self: a #YumeCircularBuffer
 * @size: the number of bytes actually write
 *
 * the write_finish function stop buffer writing and increase write pointer by
 * @size.
 *
 * returns: the number of iov actually used
 *
 * since: 4.6.1
 */
void yume_circular_buffer_write_finish (YumeCircularBuffer *self, size_t size);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_CIRCULAR_BUFFER_H__ */
