/*
 ============================================================================
 Name        : yume-task.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2025 everyone.
 Description :
 ============================================================================
 */

#ifndef __YUME_TASK_H__
#define __YUME_TASK_H__

#include <poll.h>

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_TASK_PRIORITY_MIN (0)
#define YUME_TASK_PRIORITY_MAX (15)

#define YUME_TASK_PRIORITY_HIGH YUME_TASK_PRIORITY_MIN
#define YUME_TASK_PRIORITY_LOW YUME_TASK_PRIORITY_MAX

#define YUME_TASK_PRIORITY_DEFAULT (8)
#define YUME_TASK_PRIORITY_REALTIME (0)

typedef struct _YumeTask YumeTask;
typedef void (*YumeTaskEntry) (void *data);

/**
 * YumeTaskState:
 * @YUME_TASK_STOPPED: The task is not in any task system.
 * @YUME_TASK_RUNNING: The task is in a task system's running tree.
 * @YUME_TASK_WAITING: The task is in a task system's waiting poll.
 *
 * Since: 1.0
 */
typedef enum
{
    YUME_TASK_STOPPED,
    YUME_TASK_RUNNING,
    YUME_TASK_WAITING,
} YumeTaskState;

/**
 * YumeTaskYieldType:
 * @YUME_TASK_YIELD: Move task to yield waiting state.
 * @YUME_TASK_WAITIO: Move task to I/O waiting state.
 * @YUME_TASK_YIELD_COUNT: Maximum yield type count.
 *
 * Since: 1.0
 */
typedef enum
{
    YUME_TASK_YIELD = 1,
    YUME_TASK_WAITIO,
    YUME_TASK_YIELD_COUNT,
} YumeTaskYieldType;

/**
 * yume_task_new:
 * @stack_size: stack size for task
 *
 * Creates a new task. If @stack_size = -1, the default stack size
 * will be used.
 *
 * Returns: a new #YumeTask.
 *
 * Since: 1.0
 */
YumeTask *yume_task_new (int stack_size);

/**
 * yume_task_ref:
 * @self: a #YumeTask
 *
 * Increases the reference count of the @self by one.
 *
 * Returns: a #YumeTask
 *
 * Since: 1.0
 */
YumeTask *yume_task_ref (YumeTask *self);

/**
 * yume_task_unref:
 * @self: a #YumeTask
 *
 * Decreases the reference count of @self. When its reference count
 * drops to 0, the object is finalized (i.e. its memory is freed).
 *
 * Since: 1.0
 */
void yume_task_unref (YumeTask *self);

/**
 * yume_task_self:
 *
 * Get the current task.
 *
 * Returns: a #YumeTask
 *
 * Since: 1.0
 */
YumeTask *yume_task_self (void);

/**
 * yume_task_get_state:
 * @self: a #YumeTask
 *
 * Get the state of a task.
 *
 * Returns: a #YumeTaskState
 *
 * Since: 1.0
 */
YumeTaskState yume_task_get_state (YumeTask *self);

/**
 * yume_task_set_priority:
 * @self: a #YumeTask
 * @priority: priority
 *
 * Set the priority of a task. The value range of priority are [0-1],
 * with smaller values representing higher priorities.
 *
 * Since: 1.0
 */
void yume_task_set_priority (YumeTask *self, int priority);

/**
 * yume_task_get_priority:
 * @self: a #YumeTask
 *
 * Get the priority of a task.
 *
 * Returns: current priority of task
 *
 * Since: 1.0
 */
int yume_task_get_priority (YumeTask *self);

/**
 * yume_task_add_fd:
 * @self: a #YumeTask
 * @fd: a file descriptor
 * @events: a poll events. (e.g. POLLIN, POLLOUT)
 *
 * Add a file descriptor to I/O reactor of task system. The task system will
 * wake up the task when I/O events ready.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 1.0
 */
int yume_task_add_fd (YumeTask *self, int fd, unsigned int events);

/**
 * yume_task_mod_fd:
 * @self: a #YumeTask
 * @fd: a file descriptor
 * @events: a poll events.
 *
 * Modify events of a file descriptor that added into I/O reactor of task
 * system.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 1.0
 */
int yume_task_mod_fd (YumeTask *self, int fd, unsigned int events);

/**
 * yume_task_del_fd:
 * @self: a #YumeTask
 * @fd: a file descriptor
 *
 * Remove a file descriptor from I/O reactor of task system.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 1.0
 */
int yume_task_del_fd (YumeTask *self, int fd);

/**
 * yume_task_add_whandle:
 * @self: a #YumeTask
 * @handle: a waitable handle
 *
 * Add a waitable handle to I/O reactor of task system. The task system will
 * wake up the task when signaled.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 5.8
 */
int yume_task_add_whandle (YumeTask *self, void *handle);

/**
 * yume_task_del_whandle:
 * @self: a #YumeTask
 * @handle: a waitable handle
 *
 * Remove a waitable handle from I/O reactor of task system.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 5.8
 */
int yume_task_del_whandle (YumeTask *self, void *handle);

/**
 * yume_task_wakeup:
 * @self: a #YumeTask
 *
 * Wake up a task. Don't switch tasks immediately.
 *
 * Since: 1.0
 */
void yume_task_wakeup (YumeTask *task);

/**
 * yume_task_yield:
 * @type: type of #YumeTaskYieldType
 *
 * Save current task context, pick a new task and switch to.
 *
 * Since: 1.0
 */
void yume_task_yield (YumeTaskYieldType type);

/**
 * yume_task_sleep:
 * @milliseconds: time to sleep
 *
 * Like yield. The task will be waked up by two condition:
 * 1. Timer. time has elapsed.
 * 2. I/O events. The task will be waked up by file descriptors events.
 *
 * Returns: Zero if the requested time has elapsed, or
 * the number of milliseconds left to sleep.
 *
 * Since: 1.0
 */
unsigned int yume_task_sleep (unsigned int milliseconds);

/**
 * yume_task_usleep:
 * @microseconds: time to sleep
 *
 * Like yield. The task will be waked up by two condition:
 * 1. Timer. time has elapsed.
 * 2. I/O events. The task will be waked up by file descriptors events.
 *
 * Returns: Zero if the requested time has elapsed, or
 * the number of microseconds left to sleep.
 *
 * Deprecated: 5.4.0
 */
unsigned int yume_task_usleep (unsigned int microseconds);

/**
 * yume_task_run:
 * @self (transfer full): a #YumeTask
 * @entry: A #YumeTaskEntry
 * @data (nullable): a user data to passed to @entry
 *
 * Set the entry and data to the task, and add to running tree of task system.
 * The task will be really run after task system run or current task yield.
 *
 * Since: 1.0
 */
void yume_task_run (YumeTask *self, YumeTaskEntry entry, void *data);

/**
 * yume_task_exit:
 *
 * The function terminates the calling process immediately.
 *
 * Since: 1.4
 */
void yume_task_exit (void);

/**
 * yume_task_join:
 * @task: a #YumeTask
 *
 * Join with a terminated task.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 5.2.6
 */
int yume_task_join (YumeTask *task);

/**
 * yume_task_get_data:
 * @self: a #YumeTask
 *
 * Get the user data of a task that set by run.
 *
 * Returns: current data of task.
 *
 * Since: 3.3.4
 */
void *yume_task_get_data (YumeTask *self);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_H__ */
