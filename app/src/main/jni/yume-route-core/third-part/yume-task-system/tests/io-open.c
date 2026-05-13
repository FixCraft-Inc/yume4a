/*
 ============================================================================
 Name        : io-open.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : IO Open Test
 ============================================================================
 */

#include <stdlib.h>
#include <assert.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io.h>

static void
task_entry (void *data)
{
    int fd;
    const char *path = "/tmp/.task-system";

    fd = yume_task_io_open ("/dev/zero", O_RDWR);
    assert (fd >= 0);
    assert ((fcntl (fd, F_GETFL) & O_NONBLOCK) == O_NONBLOCK);
    close (fd);

    unlink (path);
    fd = yume_task_io_open (path, O_RDWR | O_CREAT, 0644);
    assert (fd >= 0);
    assert ((fcntl (fd, F_GETFL) & O_NONBLOCK) == O_NONBLOCK);
    close (fd);
    assert (unlink (path) == 0);
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
