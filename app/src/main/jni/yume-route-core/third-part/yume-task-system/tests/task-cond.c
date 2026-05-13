/*
 ============================================================================
 Name        : task-cond.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Task Condition Test
 ============================================================================
 */

#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-cond.h>
#include <yume-task-system.h>

static int count;
static YumeTaskMutex mutex;
static YumeTaskCond cond;

static void
task_producer_entry (void *data)
{
    assert (yume_task_mutex_lock (&mutex) == 0);
    if (count == 0)
        assert (yume_task_cond_signal (&cond) == 0);
    count++;
    assert (yume_task_mutex_unlock (&mutex) == 0);

    yume_task_sleep (50);

    assert (yume_task_mutex_lock (&mutex) == 0);
    if (count == 0)
        assert (yume_task_cond_broadcast (&cond) == 0);
    count += 10;
    assert (yume_task_mutex_unlock (&mutex) == 0);
}

static void
task_consumer1_entry (void *data)
{
    yume_task_yield (YUME_TASK_YIELD);

    assert (yume_task_mutex_lock (&mutex) == 0);
    while (count == 0)
        assert (yume_task_cond_wait (&cond, &mutex) == 0);
    count--;
    assert (yume_task_mutex_unlock (&mutex) == 0);
}

static void
task_consumer2_entry (void *data)
{
    int i;

    assert (yume_task_mutex_lock (&mutex) == 0);
    while (count == 0)
        assert (yume_task_cond_wait (&cond, &mutex) == 0);
    count--;
    assert (yume_task_mutex_unlock (&mutex) == 0);

    assert (yume_task_mutex_lock (&mutex) == 0);
    assert (count == 0);
    assert (yume_task_cond_timedwait (&cond, &mutex, 0) == -1);
    assert (yume_task_cond_timedwait (&cond, &mutex, 1) == -1);
    assert (yume_task_mutex_unlock (&mutex) == 0);

    for (i = 0; i < 10; i++) {
        YumeTask *task = yume_task_new (-1);
        assert (task);
        yume_task_set_priority (task, 1);
        yume_task_run (task, task_consumer1_entry, NULL);
    }
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    assert (yume_task_mutex_init (&mutex) == 0);
    assert (yume_task_cond_init (&cond) == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_producer_entry, NULL);

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_consumer2_entry, NULL);

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
