/*
 ============================================================================
 Name        : task-channel-select.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Task Channel Select Test
 ============================================================================
 */

#include <stddef.h>
#include <stdint.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-channel.h>
#include <yume-task-channel-select.h>

#define MAX_CHANNELS (1024)

typedef struct _ChannelData ChannelData;

typedef enum
{
    CHANNEL_DATA_U8,
    CHANNEL_DATA_U16,
    CHANNEL_DATA_U32,
    CHANNEL_DATA_U64,
    CHANNEL_DATA_CHAN,
} ChannelDataType;

struct _ChannelData
{
    uint32_t type;

    union
    {
        uint8_t u8;
        uint16_t u16;
        uint32_t u32;
        uint64_t u64;
        YumeTaskChannel *chan;
    };
};

static void
task_read_entry (void *data)
{
    YumeTaskChannelSelect *sel;
    YumeTaskChannel *chan = data;
    YumeTaskChannel *chans[MAX_CHANNELS];
    size_t i, chan_count = 0;

    sel = yume_task_channel_select_new ();
    assert (sel != NULL);

    yume_task_channel_select_add (sel, chan);

    for (;;) {
        YumeTaskChannel *c;
        ChannelData d = { 0 };
        const size_t ds = sizeof (d);

        c = yume_task_channel_select_read (sel, 100);
        if (!c)
            break;

        assert (yume_task_channel_read (c, &d, ds) == ds);

        switch (d.type) {
        case CHANNEL_DATA_U8:
            assert (d.u8 == 123);
            break;
        case CHANNEL_DATA_U16:
            assert (d.u16 == 32767);
            break;
        case CHANNEL_DATA_U32:
            assert (d.u32 == 387563847);
            break;
        case CHANNEL_DATA_U64:
            assert (d.u64 == 3784597313707128903);
            break;
        case CHANNEL_DATA_CHAN:
            yume_task_channel_select_add (sel, d.chan);
            chans[chan_count] = d.chan;
            chan_count++;
            break;
        }
    }

    assert (yume_task_channel_select_read (sel, 0) == NULL);
    assert (yume_task_channel_select_read (sel, 1) == NULL);

    for (i = 0; i < chan_count; i++) {
        yume_task_channel_select_del (sel, chans[i]);
        yume_task_channel_destroy (chans[i]);
    }
    yume_task_channel_select_del (sel, chan);
    yume_task_channel_destroy (chan);
    yume_task_channel_select_destroy (sel);
}

static void
task_write_u8_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int i;

    for (i = 0; i < 8; i++) {
        ChannelData d;
        const size_t ds = sizeof (d);

        d.type = CHANNEL_DATA_U8;
        d.u8 = 123;
        assert (yume_task_channel_write (chan, &d, ds) == ds);
    }

    yume_task_channel_destroy (chan);
}

static void
task_write_u16_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int i;

    for (i = 0; i < 16; i++) {
        ChannelData d;
        const size_t ds = sizeof (d);

        d.type = CHANNEL_DATA_U16;
        d.u16 = 32767;
        assert (yume_task_channel_write (chan, &d, ds) == ds);
    }

    yume_task_channel_destroy (chan);
}

static void
task_write_u32_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int i;

    for (i = 0; i < 32; i++) {
        ChannelData d;
        const size_t ds = sizeof (d);

        d.type = CHANNEL_DATA_U32;
        d.u32 = 387563847;
        assert (yume_task_channel_write (chan, &d, ds) == ds);
    }

    yume_task_channel_destroy (chan);
}

static void
task_write_u64_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int i;

    for (i = 0; i < 64; i++) {
        ChannelData d;
        const size_t ds = sizeof (d);

        d.type = CHANNEL_DATA_U64;
        d.u64 = 3784597313707128903;
        assert (yume_task_channel_write (chan, &d, ds) == ds);
    }

    yume_task_channel_destroy (chan);
}

static void
task_fork_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int i;

    for (i = 0; i < MAX_CHANNELS; i++) {
        YumeTaskChannel *chan1, *chan2;
        YumeTask *task;
        ChannelData d;
        const size_t ds = sizeof (d);
        static const YumeTaskEntry entries[] = {
            task_write_u8_entry,
            task_write_u16_entry,
            task_write_u32_entry,
            task_write_u64_entry,
        };

        assert (yume_task_channel_new (&chan1, &chan2) == 0);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, i % (YUME_TASK_PRIORITY_MAX + 1));
        yume_task_run (task, entries[i % 4], chan1);

        d.type = CHANNEL_DATA_CHAN;
        d.chan = chan2;
        assert (yume_task_channel_write (chan, &d, ds) == ds);
    }

    yume_task_channel_destroy (chan);
}

static void
task_select_entry (void *data)
{
    YumeTaskChannel *chans[MAX_CHANNELS];
    YumeTaskChannel *chan = data;
    YumeTaskChannelSelect *sel;
    int i;

    sel = yume_task_channel_select_new ();
    assert (sel != NULL);

    for (i = 0; i < MAX_CHANNELS; i++) {
        YumeTask *task;

        assert (yume_task_channel_new (&chan, &chans[i]) == 0);
        yume_task_channel_select_add (sel, chans[i]);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 1);
        yume_task_run (task, task_read_entry, chan);
    }

    for (i = 0; i < MAX_CHANNELS; i++) {
        YumeTaskChannel *c;
        ChannelData d;
        const size_t ds = sizeof (d);

        c = yume_task_channel_select_write (sel, 100);
        if (!c)
            break;

        d.type = CHANNEL_DATA_U64;
        d.u64 = 3784597313707128903;
        assert (yume_task_channel_write (c, &d, ds) == ds);
    }

    assert (yume_task_channel_select_write (sel, 0) == NULL);
    assert (yume_task_channel_select_write (sel, 1) == NULL);

    for (i = 0; i < MAX_CHANNELS; i++) {
        yume_task_channel_select_del (sel, chans[i]);
        yume_task_channel_destroy (chans[i]);
    }
    yume_task_channel_select_destroy (sel);
}

int
main (int argc, char *argv[])
{
    YumeTaskChannel *chan1, *chan2;
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    assert (yume_task_channel_new (&chan1, &chan2) == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_read_entry, chan1);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_fork_entry, chan2);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_select_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
