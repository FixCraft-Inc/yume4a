/*
 ============================================================================
 Name        : yume-task-system.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2021 - 2024 everyone.
 Description :
 ============================================================================
 */

#ifndef __YUME_TASK_SYSTEM_H__
#define __YUME_TASK_SYSTEM_H__

#ifdef __cplusplus
extern "C" {
#endif

#define YUME_TASK_SYSTEM_MAJOR_VERSION (5)
#define YUME_TASK_SYSTEM_MINOR_VERSION (10)
#define YUME_TASK_SYSTEM_MICRO_VERSION (1)

/**
 * yume_task_system_init:
 *
 * Initialize the task system.
 *
 * Returns: When successful, returns zero. When an error occurs, returns -1.
 *
 * Since: 1.0
 */
int yume_task_system_init (void);

/**
 * yume_task_system_fini:
 *
 * Finalize the task system.
 *
 * Since: 1.0
 */
void yume_task_system_fini (void);

/**
 * yume_task_system_run:
 *
 * Run the task system.
 *
 * Since: 1.0
 */
void yume_task_system_run (void);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_SYSTEM_H__ */
