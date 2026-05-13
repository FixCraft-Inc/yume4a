/*
 ============================================================================
 Name        : task-state.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2023 everyone.
 Description : Task Join Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

static void
task1_entry (void *data)
{
    yume_task_sleep (10);
}

static void
task2_entry (void *data)
{
    YumeTask *task1 = data;

    assert (yume_task_join (task1) == 0);
    yume_task_unref (task1);
}

int
main (int argc, char *argv[])
{
    YumeTask *task1, *task2;

    assert (yume_task_system_init () == 0);

    task1 = yume_task_new (-1);
    assert (task1);
    yume_task_run (task1, task1_entry, NULL);

    task2 = yume_task_new (-1);
    assert (task2);
    yume_task_ref (task1);
    yume_task_run (task2, task2_entry, task1);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
