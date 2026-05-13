/*
 ============================================================================
 Name        : task-mutex.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Task Mutex Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-mutex.h>
#include <yume-task-system.h>

static int count;
static YumeTaskMutex mutex;

static void
task1_entry (void *data)
{
    assert (yume_task_mutex_trylock (&mutex) == 0);
    assert (yume_task_mutex_trylock (&mutex) == -1);
    assert (yume_task_mutex_unlock (&mutex) == 0);

    assert (yume_task_mutex_lock (&mutex) == 0);
    yume_task_yield (YUME_TASK_YIELD);
    assert (count == 0);
    assert (yume_task_mutex_unlock (&mutex) == 0);
}

static void
task2_entry (void *data)
{
    assert (yume_task_mutex_lock (&mutex) == 0);
    count++;
    assert (yume_task_mutex_unlock (&mutex) == 0);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    assert (yume_task_mutex_init (&mutex) == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task1_entry, NULL);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task2_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
