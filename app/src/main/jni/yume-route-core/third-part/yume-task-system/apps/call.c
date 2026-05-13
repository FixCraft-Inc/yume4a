/*
 ============================================================================
 Name        : call.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description :
 ============================================================================
 */

#include <stdio.h>

#include <yume-task.h>
#include <yume-task-call.h>
#include <yume-task-system.h>

static void
call_entry (YumeTaskCall *call)
{
    void *ptr;

    printf ("call %p on stack %p\n", call, &ptr);
    yume_task_call_set_retval (call, &ptr);
}

static void
task_entry (void *data)
{
    YumeTaskCall *call;

    call = yume_task_call_new (sizeof (YumeTaskCall), 8192);
    yume_task_call_jump (call, call_entry);
    yume_task_call_destroy (call);
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
