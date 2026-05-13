/*
 ============================================================================
 Name        : yume-debugger.h
 Authors     : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description : Debugger
 ============================================================================
 */

#ifndef __YUME_DEBUGGER_H__
#define __YUME_DEBUGGER_H__

#ifdef __cplusplus
extern "C" {
#endif

YumeTaskSystemContext *_yume_task_system_get_context (void);
unsigned int _yume_task_system_get_total_task_count (void);
unsigned int _yume_task_system_get_running_task_count (void);
YumeTask *_yume_task_system_get_current_task (void);
void _yume_task_system_dump_all_tasks (void);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_DEBUGGER_H__ */
