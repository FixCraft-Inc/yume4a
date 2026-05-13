/*
 ============================================================================
 Name        : task-ref.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : Task Reference Test
 ============================================================================
 */

#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

int
main (int argc, char *argv[])
{
    YumeTask *task;

    yume_task_system_init ();

    task = yume_task_new (-1);
    assert (task);
    assert (yume_task_ref (task) == task);

    yume_task_unref (task);
    yume_task_unref (task);

    yume_task_system_fini ();

    return 0;
}
