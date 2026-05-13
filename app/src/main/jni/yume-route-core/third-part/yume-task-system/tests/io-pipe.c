/*
 ============================================================================
 Name        : io-pipe.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : IO Pipe Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>
#include <unistd.h>
#include <fcntl.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io-pipe.h>

static void
task_entry (void *data)
{
    int fds[2];

    assert (yume_task_io_pipe_pipe (fds) == 0);
    assert (fds[0] >= 0);
    assert (fds[1] >= 0);
    assert ((fcntl (fds[0], F_GETFL) & O_NONBLOCK) == O_NONBLOCK);
    assert ((fcntl (fds[1], F_GETFL) & O_NONBLOCK) == O_NONBLOCK);
    close (fds[0]);
    close (fds[1]);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
