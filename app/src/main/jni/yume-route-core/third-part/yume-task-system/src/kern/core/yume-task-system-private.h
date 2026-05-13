/*
 ============================================================================
 Name        : yume-task-system-private.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2025 everyone.
 Description :
 ============================================================================
 */

#ifndef __YUME_TASK_SYSTEM_PRIVATE_H__
#define __YUME_TASK_SYSTEM_PRIVATE_H__

#include <time.h>
#include <setjmp.h>

#include "yume-task-system.h"
#include "kern/task/yume-task.h"
#include "kern/task/yume-task-private.h"
#include "kern/time/yume-task-timer.h"
#include "kern/io/yume-task-io-reactor.h"
#include "lib/list/yume-list.h"
#include "lib/dns/yume-task-dns-proxy.h"
#include "lib/rbtree/yume-rbtree-cached.h"
#include "lib/misc/yume-task-stack-detector.h"

#define CLOCK_NONE (-1)
#define YUME_TASK_RUN_SCHEDULER YUME_TASK_YIELD_COUNT
#define PRIORITY_COUNT (YUME_TASK_PRIORITY_MAX - YUME_TASK_PRIORITY_MIN + 1)

typedef struct _YumeTaskSystemContext YumeTaskSystemContext;

enum
{
    YUME_TASK_SCHED_SWITCH = YUME_TASK_YIELD,
    YUME_TASK_SCHED_WAITIO = YUME_TASK_WAITIO,
    YUME_TASK_SCHED_REMOVE = YUME_TASK_YIELD_COUNT,
};

struct _YumeTaskSystemContext
{
    unsigned int total_task_count;
    unsigned int running_task_count;

    YumeTaskTimer *timer;
    YumeTaskIOReactor *reactor;
    YumeTaskDNSProxy *dns_proxy;
    YumeTaskStackDetector *stack_detector;

    YumeTask *current_task;
    YumeRBTreeCached running_tasks;

    struct timespec sched_time;

    jmp_buf kernel_context;

    YumeList all_tasks;
};

void yume_task_system_schedule (YumeTaskYieldType type);
void yume_task_system_wakeup_task (YumeTask *task);
void yume_task_system_run_new_task (YumeTask *task);
void yume_task_system_kill_current_task (void);

void yume_task_system_wakeup_task_with_context (YumeTaskSystemContext *ctx,
                                               YumeTask *task);

YumeTaskSystemContext *yume_task_system_get_context (void);

#endif /* __YUME_TASK_SYSTEM_PRIVATE_H__ */
