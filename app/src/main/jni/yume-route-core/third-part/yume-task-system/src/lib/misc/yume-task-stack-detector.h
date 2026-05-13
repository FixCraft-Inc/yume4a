/*
 ============================================================================
 Name        : yume-task-stack-detector.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 hev.
 Description : Task Stack Overflow Detector
 ============================================================================
 */

#ifndef __YUME_TASK_STACK_DETECTOR_H__
#define __YUME_TASK_STACK_DETECTOR_H__

typedef struct _YumeTaskStackDetector YumeTaskStackDetector;

YumeTaskStackDetector *yume_task_stack_detector_new (void);
void yume_task_stack_detector_destroy (YumeTaskStackDetector *self);

#endif /* __YUME_TASK_STACK_DETECTOR_H__ */
