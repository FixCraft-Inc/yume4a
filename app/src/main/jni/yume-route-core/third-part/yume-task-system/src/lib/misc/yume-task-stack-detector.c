/*
 ============================================================================
 Name        : yume-task-stack-detector.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev.
 Description : Task Stack Overflow Detector
 ============================================================================
 */

#include <stdio.h>
#include <stdlib.h>
#include <signal.h>

#if defined(__APPLE__)
#include <Availability.h>
#include <AvailabilityMacros.h>
#include <TargetConditionals.h>
#endif

#include "kern/core/yume-task-system-private.h"
#include "kern/task/yume-task-private.h"
#include "kern/task/yume-task-stack.h"
#include "lib/misc/yume-compiler.h"

#include "yume-task-stack-detector.h"

struct _YumeTaskStackDetector
{
    struct sigaction bus_sa;
    struct sigaction segv_sa;
    stack_t oss;
    char stack[SIGSTKSZ];
};

static int
setaltstack (const stack_t *ss, stack_t *oss)
{
#if defined(TARGET_OS_TV) && TARGET_OS_TV
    return 0;
#else
    return sigaltstack (ss, oss);
#endif
}

static void
signal_handler (int signo, siginfo_t *si, void *unused)
{
    YumeTaskSystemContext *context = yume_task_system_get_context ();
    YumeListNode *node = yume_list_first (&context->all_tasks);
    YumeTaskStackDetector *self = context->stack_detector;

    for (; node; node = yume_list_node_next (node)) {
        YumeTask *task = container_of (node, YumeTask, list_node);
        const void *stack_base, *stack_bottom;

        stack_base = yume_task_stack_get_base (task->stack);
        stack_bottom = yume_task_stack_get_bottom (task->stack);

        if (stack_base <= si->si_addr && si->si_addr <= stack_bottom) {
            fprintf (stderr, "========== Oops! Stack overflow! ==========\n");
            fprintf (stderr, "Task: %p\n", task);
            fprintf (stderr, "  Stack   : %p - %p\n", stack_base, stack_bottom);
            fprintf (stderr, "  Bad addr: %p\n", si->si_addr);
            fprintf (stderr, "===========================================\n");
        }
    }

    switch (signo) {
    case SIGBUS:
        self->bus_sa.sa_sigaction (signo, si, unused);
        break;
    case SIGSEGV:
        self->segv_sa.sa_sigaction (signo, si, unused);
        break;
    default:
        return;
    }
}

YumeTaskStackDetector *
yume_task_stack_detector_new (void)
{
    YumeTaskStackDetector *self;
    struct sigaction sa;
    int res = 0;
    stack_t ss;

    self = malloc (sizeof (YumeTaskStackDetector));
    if (!self)
        goto exit;

    ss.ss_sp = self->stack;
    ss.ss_size = SIGSTKSZ;
    ss.ss_flags = 0;

    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sa.sa_sigaction = signal_handler;
    sigemptyset (&sa.sa_mask);

    res |= setaltstack (&ss, &self->oss);
    res |= sigaction (SIGBUS, &sa, &self->bus_sa);
    res |= sigaction (SIGSEGV, &sa, &self->segv_sa);
    if (res < 0)
        goto free;

    return self;

free:
    free (self);
exit:
    return NULL;
}

void
yume_task_stack_detector_destroy (YumeTaskStackDetector *self)
{
    sigaction (SIGBUS, &self->bus_sa, NULL);
    sigaction (SIGSEGV, &self->segv_sa, NULL);
    setaltstack (&self->oss, NULL);
    free (self);
}
