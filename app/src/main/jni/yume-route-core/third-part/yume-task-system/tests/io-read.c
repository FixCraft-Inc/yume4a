/*
 ============================================================================
 Name        : io-read.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : IO Read Test
 ============================================================================
 */

#include <assert.h>
#include <unistd.h>
#include <fcntl.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>

static void
task_entry (void *data)
{
    YumeTask *task = yume_task_self ();
    int fds[2];
    int flags;
    char buf[4];
    ssize_t size;

    assert (yume_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, fds) == 0);

    flags = fcntl (fds[1], F_GETFL);
    assert (flags >= 0);
    assert (fcntl (fds[1], F_SETFL, flags & ~O_NONBLOCK) == 0);
    assert (write (fds[1], buf, 4) == 4);

    assert (yume_task_add_fd (task, fds[0], POLLIN) == 0);

    size = yume_task_io_read (fds[0], buf, 4, NULL, NULL);
    assert (size >= 0);

    assert (yume_task_del_fd (task, fds[0]) == 0);
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
