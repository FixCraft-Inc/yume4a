/*
 ============================================================================
 Name        : yume-task-aide.h
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2022 everyone.
 Description : Aide
 ============================================================================
 */

#ifndef __YUME_TASK_AIDE_H__
#define __YUME_TASK_AIDE_H__

typedef struct _YumeTaskAideWork YumeTaskAideWork;
typedef void (*YumeTaskAideWorkHandler) (unsigned int revents, void *data);

struct _YumeTaskAideWork
{
    int fd;
    unsigned int events;
    YumeTaskAideWorkHandler handler;
    void *data;
};

int yume_task_aide_init (void);

int yume_task_aide_add (YumeTaskAideWork *work);
int yume_task_aide_del (YumeTaskAideWork *work);

#endif /* __YUME_TASK_AIDE_H__ */
