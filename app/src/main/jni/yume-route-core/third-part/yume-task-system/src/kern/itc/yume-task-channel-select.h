/*
 ============================================================================
 Name        : yume-task-channel-select.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel Selector
 ============================================================================
 */

#ifndef __YUME_TASK_CHANNEL_SELECT_H__
#define __YUME_TASK_CHANNEL_SELECT_H__

#include <sys/types.h>

#include "yume-task-channel.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeTaskChannelSelect YumeTaskChannelSelect;

/**
 * yume_task_channel_select_new:
 *
 * Creates A task channel selector.
 *
 * Returns: A #YumeTaskChannelSelect
 *
 * Since: 4.6.9
 */
YumeTaskChannelSelect *yume_task_channel_select_new (void);

/**
 * yume_task_channel_select_destroy:
 * @self: A #YumeTaskChannelSelect
 *
 * Destroy the task channel selector.
 *
 * Since: 4.6.9
 */
void yume_task_channel_select_destroy (YumeTaskChannelSelect *self);

/**
 * yume_task_channel_select_add:
 * @self: A #YumeTaskChannelSelect
 * @chan: A #YumeTaskChannel
 *
 * Add the task channel @chan to selector @self.
 *
 * Since: 4.6.9
 */
void yume_task_channel_select_add (YumeTaskChannelSelect *self,
                                  YumeTaskChannel *chan);

/**
 * yume_task_channel_select_del:
 * @self: A #YumeTaskChannelSelect
 * @chan: A #YumeTaskChannel
 *
 * Remove the task channel @chan to selector @self.
 *
 * Since: 4.6.9
 */
void yume_task_channel_select_del (YumeTaskChannelSelect *self,
                                  YumeTaskChannel *chan);

/**
 * yume_task_channel_select_read:
 * @self: A #YumeTaskChannelSelect
 * @timeout: (milliseconds): wait timeout
 *
 * The select read function shall attempt to select a readable channel.
 * The function will wait until at least one channel is ready or timeout.
 *
 * Returns: A #YumeTaskChannel
 *
 * Since: 4.6.9
 */
YumeTaskChannel *yume_task_channel_select_read (YumeTaskChannelSelect *self,
                                              int timeout);

/**
 * yume_task_channel_select_write:
 * @self: A #YumeTaskChannelSelect
 * @timeout: (milliseconds): wait timeout
 *
 * The select write function shall attempt to select a writable channel.
 * The function will wait until at least one channel is ready or timeout.
 *
 * Returns: A #YumeTaskChannel
 *
 * Since: 5.1.0
 */
YumeTaskChannel *yume_task_channel_select_write (YumeTaskChannelSelect *self,
                                               int timeout);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_CHANNEL_SELECT_H__ */
