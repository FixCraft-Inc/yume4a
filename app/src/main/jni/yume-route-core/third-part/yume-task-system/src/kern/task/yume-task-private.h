/*
 ============================================================================
 Name        : yume-task-private.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2020 everyone.
 Description :
 ============================================================================
 */

#ifndef __YUME_TASK_PRIVATE_H__
#define __YUME_TASK_PRIVATE_H__

#include <stdint.h>
#include <setjmp.h>

#include "yume-task.h"
#include "yume-task-stack.h"
#include "lib/list/yume-list.h"
#include "lib/rbtree/yume-rbtree.h"

typedef struct _YumeTaskSchedEntity YumeTaskSchedEntity;

struct _YumeTaskSchedEntity
{
    YumeTask *task;
};

struct _YumeTask
{
    void *stack_bottom;
    YumeTaskEntry entry;
    void *data;

    uint64_t sched_key;
    YumeRBTreeNode sched_node;
    YumeTaskSchedEntity sched_entity;

    YumeTaskStack *stack;
    YumeTask *joiner;

    int ref_count;
    int priority;
    int next_priority;
    YumeTaskState state;

    jmp_buf context;

    YumeListNode list_node;
};

extern void yume_task_execute (YumeTask *self, void *executer);

#endif /* __YUME_TASK_PRIVATE_H__ */
