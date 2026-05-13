/*
 ============================================================================
 Name        : yume-task-mutex.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Mutex
 ============================================================================
 */

#ifndef __YUME_TASK_MUTEX_H__
#define __YUME_TASK_MUTEX_H__

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeTaskMutex YumeTaskMutex;
typedef struct _YumeTaskMutexNode YumeTaskMutexNode;

struct _YumeTaskMutex
{
    unsigned int locker;

    YumeTaskMutexNode *waiters;
};

/**
 * yume_task_mutex_init:
 * @self: a #YumeTaskMutex
 *
 * Initialize the task mutex.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.3
 */
int yume_task_mutex_init (YumeTaskMutex *self);

/**
 * yume_task_mutex_lock:
 * @self: a #YumeTaskMutex
 *
 * Lock the task mutex.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.3
 */
int yume_task_mutex_lock (YumeTaskMutex *self);

/**
 * yume_task_mutex_trylock:
 * @self: a #YumeTaskMutex
 *
 * Try lock the task mutex.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.3
 */
int yume_task_mutex_trylock (YumeTaskMutex *self);

/**
 * yume_task_mutex_unlock:
 * @self: a #YumeTaskMutex
 *
 * Unlock the task mutex.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 4.3
 */
int yume_task_mutex_unlock (YumeTaskMutex *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_MUTEX_H__ */
