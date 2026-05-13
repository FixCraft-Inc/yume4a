/*
 ============================================================================
 Name        : task-sleep.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : Task Sleep Test
 ============================================================================
 */

#include <time.h>
#include <stddef.h>
#include <assert.h>

#include <yume-task.h>
#include <yume-task-system.h>

static int counter;

static long
time_diff (struct timespec *sp1, struct timespec *sp2)
{
    time_t sec;
    long nsec;

    sec = sp2->tv_sec - sp1->tv_sec;
    nsec = sp2->tv_nsec - sp1->tv_nsec;
    if (nsec < 0) {
        sec--;
        nsec += 1000000000L;
    }

    return (sec * 1000) + ((nsec + 999999) / 1000000);
}

static void
task_entry11 (void *data)
{
    struct timespec sp1, sp2;

    assert (counter == 2);
    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (30) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 30);
}

static void
task_entry10 (void *data)
{
    struct timespec sp1, sp2;

    assert (counter == 1);
    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (20) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 20);
}

static void
task_entry9 (void *data)
{
    struct timespec sp1, sp2;

    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (10) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 10);
}

static void
task_entry8 (void *data)
{
    YumeTask *task = data;

    assert (counter == 2);
    yume_task_wakeup (task);
}

static void
task_entry7 (void *data)
{
    struct timespec sp1, sp2;
    unsigned int remaining;
    YumeTask *task;

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry8, yume_task_self ());

    assert (counter == 1);
    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    remaining = yume_task_sleep (20);
    assert (remaining >= 0);
    assert (remaining <= 20);
    assert (yume_task_sleep (remaining) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 20);
}

static void
task_entry6 (void *data)
{
    struct timespec sp1, sp2;

    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (10) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 10);
}

static void
task_entry5 (void *data)
{
    struct timespec sp1, sp2;

    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (20) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 20);
}

static void
task_entry4 (void *data)
{
    struct timespec sp1, sp2;

    assert (counter == 1);
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (10) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 10);
}

static void
task_entry3 (void *data)
{
    struct timespec sp1, sp2;

    assert (counter == 1);
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (20) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 20);
}

static void
task_entry2 (void *data)
{
    struct timespec sp1, sp2;

    counter++;
    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (10) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 10);
}

static void
task_entry1 (void *data)
{
    struct timespec sp1, sp2;

    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (10) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 10);

    clock_gettime (CLOCK_MONOTONIC, &sp1);
    assert (yume_task_sleep (100) == 0);
    clock_gettime (CLOCK_MONOTONIC, &sp2);
    assert (time_diff (&sp1, &sp2) >= 100);
}

int
main (int argc, char *argv[])
{
    YumeTask *task;

    assert (yume_task_system_init () == 0);

    task = yume_task_new (-1);
    assert (task);
    yume_task_run (task, task_entry1, NULL);
    yume_task_system_run ();

    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry2, NULL);
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry3, NULL);
    yume_task_system_run ();

    counter = 0;
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry4, NULL);
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry5, NULL);
    yume_task_system_run ();

    counter = 0;
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry6, NULL);
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry7, NULL);
    yume_task_system_run ();

    counter = 0;
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 1);
    yume_task_run (task, task_entry9, NULL);
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 2);
    yume_task_run (task, task_entry10, NULL);
    task = yume_task_new (-1);
    assert (task);
    yume_task_set_priority (task, 3);
    yume_task_run (task, task_entry11, NULL);
    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
