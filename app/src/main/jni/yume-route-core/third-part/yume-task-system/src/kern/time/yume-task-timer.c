/*
 ============================================================================
 Name        : yume-task-timer.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : Timer
 ============================================================================
 */

#include <stdlib.h>

#include "yume-task-timer.h"
#include "kern/core/yume-task-system-private.h"
#include "mem/api/yume-memory-allocator-api.h"
#include "lib/misc/yume-compiler.h"

typedef struct _YumeTaskTimerNode YumeTaskTimerNode;

struct _YumeTaskTimer
{
    YumeRBTreeCached sort_tree;
    YumeTaskSystemContext *ctx;
};

struct _YumeTaskTimerNode
{
    YumeRBTreeNode base;

    struct timespec expire;
    YumeTask *task;
};

static inline int
yume_task_timer_cmp (const struct timespec *a, const struct timespec *b)
{
    if (a->tv_sec < b->tv_sec)
        return -1;
    if (a->tv_sec > b->tv_sec)
        return 1;

    if (a->tv_nsec < b->tv_nsec)
        return -1;
    if (a->tv_nsec > b->tv_nsec)
        return 1;

    return 0;
}

static inline int
yume_task_timer_node_cmp (YumeTaskTimerNode *a, YumeTaskTimerNode *b)
{
    int res;

    res = yume_task_timer_cmp (&a->expire, &b->expire);
    if (res)
        return res;

    if (a < b)
        return -1;
    if (a > b)
        return 1;

    return 0;
}

static inline void
yume_task_timer_get_curr (struct timespec *curr)
{
    if (clock_gettime (CLOCK_MONOTONIC, curr) < 0)
        abort ();
}

static inline void
yume_task_timer_get_expire (struct timespec *expire, unsigned int milliseconds)
{
    expire->tv_sec += milliseconds / 1000;
    expire->tv_nsec += (milliseconds % 1000) * 1000000;
    if (expire->tv_nsec > 1000000000L) {
        expire->tv_sec++;
        expire->tv_nsec -= 1000000000L;
    }
}

static inline unsigned int
yume_task_timer_get_time (const struct timespec *curr,
                         const struct timespec *expire)
{
    time_t sec;
    long nsec;

    sec = expire->tv_sec - curr->tv_sec;
    nsec = expire->tv_nsec - curr->tv_nsec;
    if (nsec < 0) {
        sec--;
        nsec += 1000000000L;
    }

    if (sec < 0)
        return 0;

    return (sec * 1000) + ((nsec + 999999) / 1000000);
}

YumeTaskTimer *
yume_task_timer_new (void *ctx)
{
    YumeTaskTimer *self;

    self = yume_malloc0 (sizeof (YumeTaskTimer));
    if (!self)
        return NULL;

    self->ctx = ctx;

    return self;
}

void
yume_task_timer_destroy (YumeTaskTimer *self)
{
    yume_free (self);
}

int
yume_task_timer_get_timeout (YumeTaskTimer *self)
{
    YumeTaskTimerNode *this;
    YumeRBTreeNode *node;
    struct timespec curr;

    node = yume_rbtree_cached_first (&self->sort_tree);
    if (!node)
        return -1;

    yume_task_timer_get_curr (&curr);
    this = container_of (node, YumeTaskTimerNode, base);
    return yume_task_timer_get_time (&curr, &this->expire);
}

static void
yume_task_timer_wakeup (YumeTaskTimer *self, const struct timespec *curr)
{
    YumeTaskTimerNode *this;
    YumeRBTreeNode *node;

    node = yume_rbtree_cached_first (&self->sort_tree);
    if (!node)
        return;

    this = container_of (node, YumeTaskTimerNode, base);
    if (yume_task_timer_cmp (curr, &this->expire) < 0)
        return;

    yume_task_system_wakeup_task_with_context (self->ctx, this->task);
}

void
yume_task_timer_wake (YumeTaskTimer *self)
{
    YumeRBTreeNode *node;
    struct timespec curr;

    node = yume_rbtree_cached_first (&self->sort_tree);
    if (!node)
        return;

    yume_task_timer_get_curr (&curr);
    yume_task_timer_wakeup (self, &curr);
}

unsigned int
yume_task_timer_wait (YumeTaskTimer *self, unsigned int milliseconds,
                     YumeTask *task)
{
    YumeTaskTimerNode curr_node, *node = &curr_node;
    YumeRBTreeNode **new = &self->sort_tree.base.root, *parent = NULL;
    struct timespec curr;
    int leftmost = 1;

    /* get expire time */
    yume_task_timer_get_curr (&node->expire);
    yume_task_timer_get_expire (&node->expire, milliseconds);
    node->task = task;

    /* insert to sort tree */
    while (*new) {
        YumeTaskTimerNode *this = container_of (*new, YumeTaskTimerNode, base);
        int result = yume_task_timer_node_cmp (node, this);

        parent = *new;
        if (result < 0) {
            new = &((*new)->left);
        } else {
            new = &((*new)->right);
            leftmost = 0;
        }
    }
    yume_rbtree_node_link (&node->base, parent, new);
    yume_rbtree_cached_insert_color (&self->sort_tree, &node->base, leftmost);

    yume_task_yield (YUME_TASK_WAITIO);

    /* check current is first */
    if (&node->base == yume_rbtree_cached_first (&self->sort_tree))
        leftmost = 1;

    /* remove from sort tree */
    yume_rbtree_cached_erase (&self->sort_tree, &node->base);

    yume_task_timer_get_curr (&curr);
    if (leftmost)
        yume_task_timer_wakeup (self, &curr);

    /* get remaining milliseconds */
    return yume_task_timer_get_time (&curr, &curr_node.expire);
}
