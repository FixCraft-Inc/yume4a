/*
 ============================================================================
 Name        : channel.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description :
 ============================================================================
 */

#include <stdio.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-channel.h>

typedef struct _TaskArgs TaskArgs;

struct _TaskArgs
{
    int type;
    char *command;
};

static void
task_entry1 (void *data)
{
    YumeTaskChannel *chan = data;
    TaskArgs args;

    yume_task_channel_read (chan, &args, sizeof (args));
    printf ("type: %d command: %s\n", args.type, args.command);

    args.type = 2;
    args.command = "world";
    yume_task_channel_write (chan, &args, sizeof (args));

    yume_task_channel_destroy (chan);
}

static void
task_entry2 (void *data)
{
    YumeTaskChannel *chan = data;
    TaskArgs args = {
        .type = 1,
        .command = "hello",
    };

    yume_task_channel_write (chan, &args, sizeof (args));
    yume_task_channel_read (chan, &args, sizeof (args));
    printf ("type: %d command: %s\n", args.type, args.command);

    yume_task_channel_destroy (chan);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;
    YumeTaskChannel *chan1, *chan2;

    yume_task_system_init ();

    yume_task_channel_new (&chan1, &chan2);

    task = yume_task_new (-1);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry1, chan1);

    task = yume_task_new (-1);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry2, chan2);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
