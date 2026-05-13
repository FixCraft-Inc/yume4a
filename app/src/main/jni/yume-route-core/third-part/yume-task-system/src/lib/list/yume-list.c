/*
 ============================================================================
 Name        : yume-list.c
 Authors     : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Double-linked List
 ============================================================================
 */

#include <stddef.h>

#include "yume-list.h"

void
yume_list_add_tail (YumeList *self, YumeListNode *new_)
{
    new_->prev = self->tail;
    new_->next = NULL;

    if (self->tail)
        self->tail->next = new_;
    else
        self->head = new_;
    self->tail = new_;
}

void
yume_list_del (YumeList *self, YumeListNode *node)
{
    if (node->prev)
        node->prev->next = node->next;
    else
        self->head = node->next;

    if (node->next)
        node->next->prev = node->prev;
    else
        self->tail = node->prev;
}
