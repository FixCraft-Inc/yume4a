/*
 ============================================================================
 Name        : yume-task-executer.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

#include "yume-task-executer.h"
#include "kern/core/yume-task-system-private.h"

void
yume_task_executer (YumeTask *task)
{
    if (_setjmp (task->context) == 0)
        return;

    task->entry (task->data);

    if (task->joiner)
        yume_task_wakeup (task->joiner);

    yume_task_system_kill_current_task ();
}
