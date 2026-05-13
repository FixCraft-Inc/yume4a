/*
 ============================================================================
 Name        : yume-task-channel.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel
 ============================================================================
 */

#ifndef __YUME_TASK_CHANNEL_H__
#define __YUME_TASK_CHANNEL_H__

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeTaskChannel YumeTaskChannel;

/**
 * yume_task_channel_new:
 * @chan1: (out): a #YumeTaskChannel
 * @chan2: (out): a #YumeTaskChannel
 *
 * Creates a pair of connected task channel.
 * The channels are synchronous, both sides of the channel will wait until the
 * other side is ready.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.5
 */
int yume_task_channel_new (YumeTaskChannel **chan1, YumeTaskChannel **chan2);

/**
 * yume_task_channel_new_with_buffers:
 * @chan1: (out): a #YumeTaskChannel
 * @chan2: (out): a #YumeTaskChannel
 * @size: buffer size
 * @buffers: buffers capacity
 *
 * Creates a pair of connected task channel with a capacity of @buffers.
 * The channels are asynchronous, sending or receiving a data will not wait
 * unless the channel is already full.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 5.1.0
 */
int yume_task_channel_new_with_buffers (YumeTaskChannel **chan1,
                                       YumeTaskChannel **chan2,
                                       unsigned int size, unsigned int buffers);

/**
 * yume_task_channel_destroy:
 * @self: a #YumeTaskChannel
 *
 * Destroy the task channel.
 *
 * Since: 4.5
 */
void yume_task_channel_destroy (YumeTaskChannel *self);

/**
 * yume_task_channel_read:
 * @self: a #YumeTaskChannel
 * @buffer: (array length=count): a buffer to read data into
 * @count: the number of bytes that will be read
 *
 * The read function shall attempt to read @count bytes from the channel into
 * @buffer. Just as datagram which the data transfer on task channel.
 *
 * Returns: the number of bytes actually read
 *
 * Since: 4.5
 */
ssize_t yume_task_channel_read (YumeTaskChannel *self, void *buffer,
                               size_t count);

/**
 * yume_task_channel_write:
 * @self: a #YumeTaskChannel
 * @buffer: (array length=count): a buffer to write data from
 * @count: the number of bytes that will be write
 *
 * The write function shall attempt to write @count bytes to the channel from
 * @buffer. Just as datagram which the data transfer on task channel.
 *
 * Returns: the number of bytes actually write
 *
 * Since: 4.5
 */
ssize_t yume_task_channel_write (YumeTaskChannel *self, const void *buffer,
                                size_t count);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_CHANNEL_H__ */
