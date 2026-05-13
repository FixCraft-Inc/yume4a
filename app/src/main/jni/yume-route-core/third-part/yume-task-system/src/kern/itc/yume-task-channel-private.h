/*
 ============================================================================
 Name        : yume-task-channel-private.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel
 ============================================================================
 */

#ifndef __YUME_TASK_CHANNEL_PRIVATE_H__
#define __YUME_TASK_CHANNEL_PRIVATE_H__

#include "lib/list/yume-list.h"
#include "kern/task/yume-task.h"

#include "yume-task-channel-select.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef union _YumeTaskChannelData YumeTaskChannelData;
typedef struct _YumeTaskChannelBuffer YumeTaskChannelBuffer;

union _YumeTaskChannelData
{
    char b[0];
    short h[0];
    int w[0];
    long long d[0];
};

struct _YumeTaskChannelBuffer
{
    size_t size;
    YumeTaskChannelData *data;
};

struct _YumeTaskChannel
{
    YumeTaskChannel *peer;

    struct
    {
        YumeListNode node;
        int used;
    } read;
    struct
    {
        YumeListNode node;
        int used;
    } write;
    YumeListNode chan_node;

    YumeTask *task;
    YumeTaskChannelSelect *select;

    unsigned int rd_idx;
    unsigned int wr_idx;
    unsigned int max_size;
    unsigned int use_count;
    unsigned int max_count;
    unsigned int ref_count;

    YumeTaskChannelBuffer buffers[0];
};

static inline int
yume_task_channel_is_active (YumeTaskChannel *self)
{
    return READ_ONCE (self->peer) != NULL;
}

static inline int
yume_task_channel_is_readable (YumeTaskChannel *self)
{
    return READ_ONCE (self->use_count) != 0;
}

static inline int
yume_task_channel_is_writable (YumeTaskChannel *peer)
{
    return READ_ONCE (peer->use_count) < READ_ONCE (peer->max_count);
}

static inline int
yume_task_channel_is_select_writable (YumeTaskChannel *peer)
{
    return READ_ONCE (peer->use_count) < (READ_ONCE (peer->max_count) - 1);
}

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_CHANNEL_PRIVATE_H__ */
