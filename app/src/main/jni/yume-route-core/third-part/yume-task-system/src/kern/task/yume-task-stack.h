/*
 ============================================================================
 Name        : yume-task-stack.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description :
 ============================================================================
 */

#ifndef __YUME_TASK_STACK_H__
#define __YUME_TASK_STACK_H__

#define STACK_HEAP (0)
#define STACK_MMAP (1)

typedef struct _YumeTaskStack YumeTaskStack;

YumeTaskStack *yume_task_stack_new (int size);
void yume_task_stack_destroy (YumeTaskStack *self);

void *yume_task_stack_get_base (YumeTaskStack *self);
void *yume_task_stack_get_bottom (YumeTaskStack *self);

#endif /* __YUME_TASK_STACK_H__ */
