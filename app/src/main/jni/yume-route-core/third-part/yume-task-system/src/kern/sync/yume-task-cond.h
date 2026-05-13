/*
 ============================================================================
 Name        : yume-task-cond.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Condition
 ============================================================================
 */

#ifndef __YUME_TASK_COND_H__
#define __YUME_TASK_COND_H__

#include "yume-task-mutex.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeTaskCond YumeTaskCond;
typedef struct _YumeTaskCondNode YumeTaskCondNode;

struct _YumeTaskCond
{
    YumeTaskCondNode *waiters;
};

/**
 * yume_task_cond_init:
 * @self: a #YumeTaskCond
 *
 * Initialize the task condition.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.4
 */
int yume_task_cond_init (YumeTaskCond *self);

/**
 * yume_task_cond_wait:
 * @self: a #YumeTaskCond
 * @mutex: a #YumeTaskMutex
 *
 * Wait on the task condition.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.4
 */
int yume_task_cond_wait (YumeTaskCond *self, YumeTaskMutex *mutex);

/**
 * yume_task_cond_wait:
 * @self: a #YumeTaskCond
 * @mutex: a #YumeTaskMutex
 * @milliseconds: wait time
 *
 * The timedwait function shall be equivalent to condition wait, except that an
 * error is returned if the time specified by @milliseconds passes.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.4
 */
int yume_task_cond_timedwait (YumeTaskCond *self, YumeTaskMutex *mutex,
                             unsigned int milliseconds);

/**
 * yume_task_cond_signal:
 * @self: a #YumeTaskCond
 *
 * Wake one task that wait on condition.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.4
 */
int yume_task_cond_signal (YumeTaskCond *self);

/**
 * yume_task_cond_broadcast:
 * @self: a #YumeTaskCond
 *
 * Wake all task that wait on condition.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.4
 */
int yume_task_cond_broadcast (YumeTaskCond *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_MUTEX_H__ */
