/*
 ============================================================================
 Name        : task.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Task Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

static int count;

static void
task1_entry (void *data)
{
    count++;
}

static void
task2_entry (void *data)
{
    count++;
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task1_entry, NULL);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task2_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    assert (count == 2);

    return 0;
}
