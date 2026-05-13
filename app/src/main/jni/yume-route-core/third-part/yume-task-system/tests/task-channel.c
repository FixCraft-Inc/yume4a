/*
 ============================================================================
 Name        : task-channel.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Task Channel Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-channel.h>
#include <yume-task-system.h>

static void
task1_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int v = 0;
    const size_t vs = sizeof (v);

    assert (yume_task_channel_read (chan, &v, vs) == vs);
    assert (v == 1234);

    assert (yume_task_channel_read (chan, &v, vs) == vs);
    assert (v == 5678);

    assert (yume_task_channel_read (chan, &v, vs) == -1);

    yume_task_channel_destroy (chan);
}

static void
task2_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int v;
    const size_t vs = sizeof (v);

    v = 1234;
    assert (yume_task_channel_write (chan, &v, vs) == vs);

    v = 5678;
    assert (yume_task_channel_write (chan, &v, vs) == vs);

    yume_task_channel_destroy (chan);
}

static void
task3_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int v = 0;
    const size_t vs = sizeof (v);

    assert (yume_task_channel_read (chan, &v, vs) == vs);
    assert (v == 1234);

    v = 5678;
    assert (yume_task_channel_write (chan, &v, vs) == vs);

    assert (yume_task_channel_read (chan, &v, vs) == vs);
    assert (v == 9012);

    assert (yume_task_channel_write (chan, &v, vs) == -1);

    yume_task_channel_destroy (chan);
}

static void
task4_entry (void *data)
{
    YumeTaskChannel *chan = data;
    int v;
    const size_t vs = sizeof (v);

    v = 1234;
    assert (yume_task_channel_write (chan, &v, vs) == vs);

    assert (yume_task_channel_read (chan, &v, vs) == vs);
    assert (v == 5678);

    v = 9012;
    assert (yume_task_channel_write (chan, &v, vs) == vs);

    yume_task_channel_destroy (chan);
}

int
main (int argc, char *argv[])
{
    int i;

    assert (yume_task_system_init () == 0);

    for (i = 0; i < 5; i++) {
        YumeTask *task;
        YumeTaskChannel *chan1, *chan2;

        assert (yume_task_channel_new_with_buffers (&chan1, &chan2, 16, i) == 0);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 1);
        yume_task_run (task, task1_entry, chan1);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 2);
        yume_task_run (task, task2_entry, chan2);

        assert (yume_task_channel_new_with_buffers (&chan1, &chan2, 16, i) == 0);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 1);
        yume_task_run (task, task3_entry, chan1);

        task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 2);
        yume_task_run (task, task4_entry, chan2);
    }

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
