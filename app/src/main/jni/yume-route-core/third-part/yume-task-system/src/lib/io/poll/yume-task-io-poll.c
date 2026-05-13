/*
 ============================================================================
 Name        : yume-task-io-poll.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2019 everyone.
 Description :
 ============================================================================
 */

#include "kern/task/yume-task.h"
#include "lib/misc/yume-compiler.h"

#include "yume-task-io-poll.h"

EXPORT_SYMBOL int
yume_task_io_poll (YumeTaskIOPollFD fds[], unsigned int nfds, int timeout)
{
    int res;

    if (timeout == 0) {
        res = poll (fds, nfds, 0);
    } else {
        YumeTask *task = yume_task_self ();
        unsigned int i;

        for (i = 0; i < nfds; i++) {
            if (yume_task_mod_fd (task, fds[i].fd, fds[i].events) < 0)
                yume_task_add_fd (task, fds[i].fd, fds[i].events);
        }

        if (timeout > 0) {
            do {
                timeout = yume_task_sleep (timeout);
                res = poll (fds, nfds, 0);
            } while (timeout > 0 && res == 0);
        } else {
            do {
                yume_task_yield (YUME_TASK_WAITIO);
                res = poll (fds, nfds, 0);
            } while (res == 0);
        }
    }

    return res;
}
