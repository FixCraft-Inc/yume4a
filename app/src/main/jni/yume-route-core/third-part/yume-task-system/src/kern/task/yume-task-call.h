/*
 ============================================================================
 Name        : yume-task-call.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description : Task call
 ============================================================================
 */

#ifndef __YUME_TASK_CALL_H__
#define __YUME_TASK_CALL_H__

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeTaskCall YumeTaskCall;
typedef void (*YumeTaskCallEntry) (YumeTaskCall *call);

struct _YumeTaskCall
{
    void *stack_top;
    void *retval;
};

/**
 * yume_task_call_new:
 * @base_size: base size
 * @stack_size: stack size
 *
 * Creates a new task call.
 *
 * Returns: a new #YumeTaskCall.
 *
 * Since: 4.8.0
 */
YumeTaskCall *yume_task_call_new (int base_size, int stack_size);

/**
 * yume_task_call_destroy:
 * @self: a #YumeTaskCall
 *
 * Destroy the task call.
 *
 * Since: 4.8.0
 */
void yume_task_call_destroy (YumeTaskCall *self);

/**
 * yume_task_call_jump:
 * @self: a #YumeTaskCall
 * @entry: task call entry
 *
 * Call @entry on new stack.
 *
 * Returns: value from @entry (see set_retval).
 *
 * Since: 4.8.0
 */
void *yume_task_call_jump (YumeTaskCall *self, YumeTaskCallEntry entry);

/**
 * yume_task_call_set_retval:
 * @self: a #YumeTaskCall
 * @value: return value
 *
 * Set return value of task call.
 *
 * Since: 4.8.0
 */
static inline void
yume_task_call_set_retval (YumeTaskCall *self, void *value)
{
    self->retval = value;
}

#ifdef __cplusplus
}
#endif

#endif /* __YUME_TASK_CALL_H__ */
