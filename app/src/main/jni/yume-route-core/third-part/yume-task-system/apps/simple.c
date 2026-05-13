/*
 ============================================================================
 Name        : simple.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

#include <stdio.h>

#include <yume-task.h>
#include <yume-task-system.h>

static void
task_entry1 (void *data)
{
    int i;

    for (i = 0; i < 2; i++) {
        printf ("hello 1\n");
        yume_task_yield (YUME_TASK_YIELD);
    }
}

static void
task_entry2 (void *data)
{
    int i;

    for (i = 0; i < 2; i++) {
        printf ("hello 2\n");
        yume_task_yield (YUME_TASK_YIELD);
    }
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    yume_task_system_init ();

    task = yume_task_new (-1);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry1, NULL);

    task = yume_task_new (-1);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry2, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
