/*
 ============================================================================
 Name        : task-self.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Task Self Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

static YumeTask *task;

static void
task_entry (void *data)
{
    assert (yume_task_self () == task);
}

int
main (int argc, char *argv[])
{
    assert (yume_task_system_init () == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
