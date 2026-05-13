/*
 ============================================================================
 Name        : yume-task-channel-select.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel Selector
 ============================================================================
 */

#include "lib/misc/yume-compiler.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-channel-select.h"
#include "yume-task-channel-select-private.h"

EXPORT_SYMBOL YumeTaskChannelSelect *
yume_task_channel_select_new (void)
{
    YumeTaskChannelSelect *self;

    self = yume_malloc0 (sizeof (YumeTaskChannelSelect));

    return self;
}

EXPORT_SYMBOL void
yume_task_channel_select_destroy (YumeTaskChannelSelect *self)
{
    yume_free (self);
}

EXPORT_SYMBOL void
yume_task_channel_select_add (YumeTaskChannelSelect *self, YumeTaskChannel *chan)
{
    chan->select = self;
    chan->task = yume_task_self ();

    yume_list_add_tail (&self->chan_list, &chan->chan_node);
    if (yume_task_channel_is_readable (chan))
        yume_task_channel_select_add_read (self, chan);
    if (chan->peer && yume_task_channel_is_select_writable (chan->peer))
        yume_task_channel_select_add_write (self, chan);
}

EXPORT_SYMBOL void
yume_task_channel_select_del (YumeTaskChannelSelect *self, YumeTaskChannel *chan)
{
    yume_task_channel_select_del_read (self, chan);
    yume_task_channel_select_del_write (self, chan);
    yume_list_del (&self->chan_list, &chan->chan_node);

    chan->task = NULL;
    chan->select = NULL;
}

static YumeListNode *
yume_task_channel_select (YumeTaskChannelSelect *self, YumeList *list, int timeout)
{
    YumeListNode *node;
    unsigned int milliseconds = timeout;

    if (!yume_list_first (&self->chan_list))
        return NULL;

    while (!(node = yume_list_first (list)) && milliseconds) {
        barrier ();
        if (timeout < 0)
            yume_task_yield (YUME_TASK_WAITIO);
        else
            milliseconds = yume_task_sleep (milliseconds);
        barrier ();
    }

    return node;
}

EXPORT_SYMBOL YumeTaskChannel *
yume_task_channel_select_read (YumeTaskChannelSelect *self, int timeout)
{
    YumeListNode *node;

    node = yume_task_channel_select (self, &self->read_list, timeout);
    if (!node)
        return NULL;

    return container_of (node, YumeTaskChannel, read.node);
}

EXPORT_SYMBOL YumeTaskChannel *
yume_task_channel_select_write (YumeTaskChannelSelect *self, int timeout)
{
    YumeListNode *node;

    node = yume_task_channel_select (self, &self->write_list, timeout);
    if (!node)
        return NULL;

    return container_of (node, YumeTaskChannel, write.node);
}
