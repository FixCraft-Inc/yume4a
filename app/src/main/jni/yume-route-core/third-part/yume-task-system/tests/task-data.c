/*
 ============================================================================
 Name        : task-data.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Task Data Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

static int tag;

static void
task_entry (void *data)
{
    assert (data == &tag);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task_entry, &tag);

    assert (yume_task_get_data (task) == &tag);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
