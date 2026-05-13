/*
 ============================================================================
 Name        : task-call.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description : Task Call Test
 ============================================================================
 */

#include <assert.h>
#include <stdlib.h>

#include <yume-task.h>
#include <yume-task-call.h>
#include <yume-task-system.h>

static void
call_entry1 (YumeTaskCall *call)
{
    void *ptr;

    ptr = alloca (1);
    yume_task_call_set_retval (call, ptr);
}

static void
call_entry2 (YumeTaskCall *call)
{
    yume_task_call_set_retval (call, call_entry1);
}

static void
task_entry (void *data)
{
    YumeTaskCall *call;
    void *retval;

    call = yume_task_call_new (sizeof (YumeTaskCall), 8192);

    retval = yume_task_call_jump (call, call_entry1);
    assert (retval > (void *)call + sizeof (YumeTaskCall));
    assert (retval < (void *)call + sizeof (YumeTaskCall) + 8192);

    retval = yume_task_call_jump (call, call_entry2);
    assert (retval == call_entry1);

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
