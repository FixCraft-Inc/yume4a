/*
 ============================================================================
 Name        : timeout.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

#include <stdio.h>

#include <yume-task.h>
#include <yume-task-system.h>

static void
task_entry (void *data)
{
    const unsigned int interval = 1000;

    printf ("waiting for timeout %ums ...\n", interval);
    yume_task_sleep (interval);
    printf ("timeout\n");
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    yume_task_system_init ();

    task = yume_task_new (-1);
    yume_task_run (task, task_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
