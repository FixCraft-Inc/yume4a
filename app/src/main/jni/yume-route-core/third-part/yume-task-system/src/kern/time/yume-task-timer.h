/*
 ============================================================================
 Name        : yume-task-timer.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 - 2025 everyone.
 Description : Timer
 ============================================================================
 */

#ifndef __YUME_TASK_TIMER_H__
#define __YUME_TASK_TIMER_H__

#include <time.h>

#include "kern/task/yume-task.h"
#include "lib/rbtree/yume-rbtree-cached.h"

typedef struct _YumeTaskTimer YumeTaskTimer;

YumeTaskTimer *yume_task_timer_new (void *ctx);
void yume_task_timer_destroy (YumeTaskTimer *self);

int yume_task_timer_get_timeout (YumeTaskTimer *self);
void yume_task_timer_wake (YumeTaskTimer *self);

unsigned int yume_task_timer_wait (YumeTaskTimer *self, unsigned int milliseconds,
                                  YumeTask *task);

#endif /* __YUME_TASK_TIMER_H__ */
