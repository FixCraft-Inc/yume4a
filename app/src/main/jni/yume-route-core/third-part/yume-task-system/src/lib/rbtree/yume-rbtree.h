/*
 ============================================================================
 Name        : yume-rbtree.h
 Authors     : Andrea Arcangeli <andrea@suse.de>
               David Woodhouse <dwmw2@infradead.org>
               Michel Lespinasse <walken@google.com>
               Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : RedBlack Tree
 ============================================================================
 */

#ifndef __YUME_RBTREE_H__
#define __YUME_RBTREE_H__

#include <stddef.h>

typedef struct _YumeRBTree YumeRBTree;
typedef struct _YumeRBTreeNode YumeRBTreeNode;

struct _YumeRBTree
{
    YumeRBTreeNode *root;
};

struct _YumeRBTreeNode
{
    unsigned long __parent_color;
    YumeRBTreeNode *right;
    YumeRBTreeNode *left;
} __attribute__ ((aligned (sizeof (long))));

static inline int
yume_rbtree_node_empty (YumeRBTreeNode *node)
{
    return node->__parent_color == (unsigned long)node;
}

static inline YumeRBTreeNode *
yume_rbtree_node_parent (YumeRBTreeNode *node)
{
    return (YumeRBTreeNode *)(node->__parent_color & ~3);
}

static inline void
yume_rbtree_node_link (YumeRBTreeNode *node, YumeRBTreeNode *parent,
                      YumeRBTreeNode **link)
{
    node->__parent_color = (unsigned long)parent;
    node->left = node->right = NULL;

    *link = node;
}

YumeRBTreeNode *yume_rbtree_node_prev (YumeRBTreeNode *node);
YumeRBTreeNode *yume_rbtree_node_next (YumeRBTreeNode *node);

YumeRBTreeNode *yume_rbtree_first (YumeRBTree *self);
YumeRBTreeNode *yume_rbtree_last (YumeRBTree *self);

void yume_rbtree_insert_color (YumeRBTree *self, YumeRBTreeNode *node);

void yume_rbtree_replace (YumeRBTree *self, YumeRBTreeNode *victim,
                         YumeRBTreeNode *new);

void yume_rbtree_erase (YumeRBTree *self, YumeRBTreeNode *node);

#endif /* __YUME_RBTREE_H__ */
