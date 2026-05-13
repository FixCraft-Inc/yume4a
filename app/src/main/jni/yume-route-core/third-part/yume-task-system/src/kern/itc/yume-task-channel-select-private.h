/*
 ============================================================================
 Name        : yume-task-channel-select-private.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel Selector
 ============================================================================
 */

#ifndef __YUME_TASK_CHANNEL_SELECT_PRIVATE_H__
#define __YUME_TASK_CHANNEL_SELECT_PRIVATE_H__

#include "lib/list/yume-list.h"
#include "kern/task/yume-task.h"

#include "yume-task-channel.h"
#include "yume-task-channel-private.h"

#include "yume-task-channel-select.h"

#ifdef __cplusplus
extern "C" {
#endif

struct _YumeTaskChannelSelect
{
    YumeTask *task;
    YumeList chan_list;
    YumeList read_list;
    YumeList write_list;
};

static inline void
yume_task_channel_select_add_read (YumeTaskChannelSelect *self,
                                  YumeTaskChannel *chan)
{
    if (chan->read.used)
        return;

    chan->read.used = 1;
    yume_list_add_tail (&self->read_list, &chan->read.node);
}

static inline void
yume_task_channel_select_del_read (YumeTaskChannelSelect *self,
                                  YumeTaskChannel *chan)
{
    if (!chan->read.used)
        return;

    chan->read.used = 0;
    yume_list_del (&self->read_list, &chan->read.node);
}

static inline void
yume_task_channel_select_add_write (YumeTaskChannelSelect *self,
                                   YumeTaskChannel *chan)
{
    if (chan->write.used)
        return;

    chan->write.used = 1;
    yume_list_add_tail (&self->write_list, &chan->write.node);
}

static inline void
yume_task_channel_select_del_write (YumeTaskChannelSelect *self,
                                   YumeTaskChannel *chan)
{
    if (!chan->write.used)
        return;

    chan->write.used = 0;
    yume_list_del (&self->write_list, &chan->write.node);
}

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_CHANNEL_SELECT_PRIVATE_H__ */
