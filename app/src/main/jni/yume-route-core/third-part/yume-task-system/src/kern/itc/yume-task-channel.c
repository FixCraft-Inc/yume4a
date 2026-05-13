/*
 ============================================================================
 Name        : yume-task-channel.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Channel
 ============================================================================
 */

#include "kern/task/yume-task.h"
#include "lib/misc/yume-compiler.h"
#include "mem/api/yume-memory-allocator-api.h"

#include "yume-task-channel-select-private.h"

#include "yume-task-channel.h"
#include "yume-task-channel-private.h"

EXPORT_SYMBOL int
yume_task_channel_new (YumeTaskChannel **chan1, YumeTaskChannel **chan2)
{
    return yume_task_channel_new_with_buffers (chan1, chan2, 0, 0);
}

EXPORT_SYMBOL int
yume_task_channel_new_with_buffers (YumeTaskChannel **chan1,
                                   YumeTaskChannel **chan2, unsigned int size,
                                   unsigned int buffers)
{
    YumeTaskChannel *c1, *c2;
    unsigned int buffers_size;
    unsigned int data_size = 0;

    buffers += 1;
    if (buffers > 1)
        data_size = size * buffers;
    buffers_size = sizeof (YumeTaskChannelBuffer) * buffers;

    c1 = yume_malloc (sizeof (YumeTaskChannel) + buffers_size + data_size);
    if (!c1)
        goto err0;

    c2 = yume_malloc (sizeof (YumeTaskChannel) + buffers_size + data_size);
    if (!c2)
        goto err1;

    __builtin_bzero (c1, sizeof (YumeTaskChannel));
    c1->peer = c2;
    c1->max_size = size;
    c1->max_count = buffers;
    c1->ref_count = 1;

    __builtin_bzero (c2, sizeof (YumeTaskChannel));
    c2->peer = c1;
    c2->max_size = size;
    c2->max_count = buffers;
    c2->ref_count = 1;

    if (buffers > 1) {
        unsigned int i;

        for (i = 0; i < buffers; i++) {
            c1->buffers[i].data = (void *)c1->buffers + buffers_size + size * i;
            c2->buffers[i].data = (void *)c2->buffers + buffers_size + size * i;
        }
    }

    *chan1 = c1;
    *chan2 = c2;

    return 0;

    yume_free (c2);
err1:
    yume_free (c1);
err0:

    return -1;
}

static void
yume_task_channel_ref (YumeTaskChannel *self)
{
    self->ref_count++;
}

static void
yume_task_channel_unref (YumeTaskChannel *self)
{
    self->ref_count--;
    if (self->ref_count)
        return;

    yume_free (self);
}

EXPORT_SYMBOL void
yume_task_channel_destroy (YumeTaskChannel *self)
{
    if (self->peer) {
        self->peer->peer = NULL;
        if (self->peer->task)
            yume_task_wakeup (self->peer->task);
    }
    if (self->task)
        yume_task_wakeup (self->task);

    yume_task_channel_unref (self);
}

static ssize_t
yume_task_channel_data_copy (void *dst, const void *src, size_t size)
{
    YumeTaskChannelData *d = dst;
    const YumeTaskChannelData *s = src;

    switch (size) {
    case 0:
        break;
    case sizeof (char):
        d->b[0] = s->b[0];
        break;
    case sizeof (short):
        d->h[0] = s->h[0];
        break;
    case sizeof (int):
        d->w[0] = s->w[0];
        break;
    case sizeof (long long):
        d->d[0] = s->d[0];
        break;
    default:
        __builtin_memcpy (dst, src, size);
    }

    return size;
}

EXPORT_SYMBOL ssize_t
yume_task_channel_read (YumeTaskChannel *self, void *buffer, size_t count)
{
    YumeTaskChannelBuffer *cbuf;
    ssize_t size = -1;

    /* wait on empty */
    while (!yume_task_channel_is_readable (self)) {
        /* check is peer alive because cond wait may yield */
        if (!yume_task_channel_is_active (self))
            goto out;

        WRITE_ONCE (self->task, yume_task_self ());
        yume_task_yield (YUME_TASK_WAITIO);
    }

    barrier ();
    cbuf = &self->buffers[self->rd_idx];
    self->rd_idx = (self->rd_idx + 1) % self->max_count;

    size = count;
    if (cbuf->size < count)
        size = cbuf->size;
    size = yume_task_channel_data_copy (buffer, cbuf->data, size);

    if (self->use_count == self->max_count) {
        YumeTaskChannel *peer = self->peer;

        if (peer) {
            if (peer->select)
                yume_task_channel_select_add_write (peer->select, peer);
            if (peer->task)
                yume_task_wakeup (peer->task);
        }
    }

    self->use_count--;
    if (self->select && !yume_task_channel_is_readable (self))
        yume_task_channel_select_del_read (self->select, self);

out:
    return size;
}

EXPORT_SYMBOL ssize_t
yume_task_channel_write (YumeTaskChannel *self, const void *buffer, size_t count)
{
    YumeTaskChannel *peer = self->peer;
    YumeTaskChannelBuffer *cbuf;
    ssize_t size = -1;

    if (!peer)
        goto out0;

    yume_task_channel_ref (peer);

    /* wait on full */
    while (!yume_task_channel_is_writable (peer)) {
        /* check is peer alive because cond wait may yield */
        if (!yume_task_channel_is_active (self))
            goto out1;

        WRITE_ONCE (self->task, yume_task_self ());
        yume_task_yield (YUME_TASK_WAITIO);
    }

    barrier ();
    cbuf = &peer->buffers[peer->wr_idx];
    peer->wr_idx = (peer->wr_idx + 1) % peer->max_count;

    size = count;
    if (peer->max_count == 1) {
        cbuf->data = (YumeTaskChannelData *)buffer;
    } else {
        if (size > self->max_size)
            size = self->max_size;
        size = yume_task_channel_data_copy (cbuf->data, buffer, size);
    }
    cbuf->size = size;

    if (peer->use_count == 0) {
        if (peer->select)
            yume_task_channel_select_add_read (peer->select, peer);
        if (peer->task)
            yume_task_wakeup (peer->task);
    }

    peer->use_count++;
    if (self->select && !yume_task_channel_is_select_writable (peer))
        yume_task_channel_select_del_write (self->select, self);

    /* sync */
    while (!yume_task_channel_is_writable (peer)) {
        /* check is peer alive because cond wait may yield */
        if (!yume_task_channel_is_active (self)) {
            size = -1;
            break;
        }

        WRITE_ONCE (self->task, yume_task_self ());
        yume_task_yield (YUME_TASK_WAITIO);
    }

    barrier ();
out1:
    yume_task_channel_unref (peer);
out0:

    return size;
}
