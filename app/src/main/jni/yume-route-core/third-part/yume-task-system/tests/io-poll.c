/*
 ============================================================================
 Name        : io-poll.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : IO Poll Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>
#include <unistd.h>
#include <fcntl.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io.h>
#include <yume-task-io-poll.h>
#include <yume-task-io-socket.h>

static int fds[2];

static void
task1_entry (void *data)
{
    int val;
    YumeTaskIOPollFD pfds[1];

    pfds[0].fd = fds[0];
    pfds[0].events = POLLIN;

    val = yume_task_io_poll (pfds, 1, -1);
    assert (val == 1);
    assert (pfds[0].revents & POLLIN);

    close (fds[0]);
}

static void
task2_entry (void *data)
{
    int val;
    YumeTaskIOPollFD pfds[1];

    yume_task_sleep (50);

    pfds[0].fd = fds[1];
    pfds[0].events = POLLOUT;

    val = yume_task_io_poll (pfds, 1, -1);
    assert (val == 1);
    assert (pfds[0].revents & POLLOUT);

    assert (write (fds[1], &val, sizeof (val)) == sizeof (val));

    close (fds[1]);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

#if defined(__MSYS__)
    return 0;
#endif

    assert (yume_task_system_init () == 0);

    assert (yume_task_io_socket_socketpair (PF_LOCAL, SOCK_STREAM, 0, fds) == 0);
    assert (fds[0] >= 0);
    assert (fds[1] >= 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task1_entry, NULL);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task2_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
