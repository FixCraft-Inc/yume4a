/*
 ============================================================================
 Name        : yume-list.h
 Authors     : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2019 everyone.
 Description : Double-linked List
 ============================================================================
 */

#ifndef __YUME_LIST_H__
#define __YUME_LIST_H__

#ifdef __cplusplus
extern "C" {
#endif

typedef struct _YumeList YumeList;
typedef struct _YumeListNode YumeListNode;

struct _YumeList
{
    YumeListNode *head;
    YumeListNode *tail;
};

struct _YumeListNode
{
    YumeListNode *next;
    YumeListNode *prev;
};

static inline YumeListNode *
yume_list_node_prev (YumeListNode *node)
{
    return node->prev;
}

static inline YumeListNode *
yume_list_node_next (YumeListNode *node)
{
    return node->next;
}

static inline YumeListNode *
yume_list_first (YumeList *self)
{
    return self->head;
}

static inline YumeListNode *
yume_list_last (YumeList *self)
{
    return self->tail;
}

void yume_list_add_tail (YumeList *self, YumeListNode *new_);
void yume_list_del (YumeList *self, YumeListNode *node);

#ifdef __cplusplus
}
#endif

#endif /* __YUME_LIST_H__ */
