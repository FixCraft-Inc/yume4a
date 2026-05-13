/*
 ============================================================================
 Name        : system.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : System Test
 ============================================================================
 */

#include <assert.h>

#include <yume-task-system.h>

int
main (int argc, char *argv[])
{
    assert (yume_task_system_init () == 0);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
