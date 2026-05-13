/*
 ============================================================================
 Name        : io-splice.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : IO Splice Test
 ============================================================================
 */

#include <stdlib.h>
#include <assert.h>
#include <unistd.h>
#include <string.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io.h>
#include <yume-task-io-socket.h>

static int fds1[2];
static int fds2[2];

static void
task_splice_entry (void *data)
{
    YumeTask *task = yume_task_self ();

    assert (yume_task_add_fd (task, fds1[1], POLLIN | POLLOUT) == 0);
    assert (yume_task_add_fd (task, fds2[0], POLLIN | POLLOUT) == 0);

    yume_task_io_splice (fds1[1], fds1[1], fds2[0], fds2[0], 2048, NULL, NULL);

    assert (yume_task_del_fd (task, fds1[1]) == 0);
    assert (yume_task_del_fd (task, fds2[0]) == 0);

    close (fds1[1]);
    close (fds2[0]);
}

static void
task_entry (void *data)
{
    YumeTask *task = yume_task_self ();
    int result;
    char buf1[64], buf2[64];
    ssize_t size;

    result = yume_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, fds1);
    assert (result == 0);
    result = yume_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, fds2);
    assert (result == 0);

    assert (yume_task_add_fd (task, fds1[0], POLLOUT) == 0);
    assert (yume_task_add_fd (task, fds2[1], POLLIN) == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task_splice_entry, NULL);

    size = yume_task_io_socket_send (fds1[0], buf1, 64, MSG_WAITALL, NULL, NULL);
    assert (size == 64);
    size = yume_task_io_socket_recv (fds2[1], buf2, 64, MSG_WAITALL, NULL, NULL);
    assert (size == 64);

    assert (memcmp (buf1, buf2, 64) == 0);

    assert (yume_task_del_fd (task, fds1[0]) == 0);
    assert (yume_task_del_fd (task, fds2[1]) == 0);

    close (fds1[0]);
    close (fds2[1]);
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
