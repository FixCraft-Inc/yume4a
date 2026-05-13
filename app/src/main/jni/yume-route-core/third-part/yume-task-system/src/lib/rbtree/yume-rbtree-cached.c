/*
 ============================================================================
 Name        : yume-rbtree-cached.c
 Authors     : Andrea Arcangeli <andrea@suse.de>
               David Woodhouse <dwmw2@infradead.org>
               Michel Lespinasse <walken@google.com>
               Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : RedBlack Tree Cached
 ============================================================================
 */

#include "yume-rbtree-cached.h"

void
yume_rbtree_cached_insert_color (YumeRBTreeCached *self, YumeRBTreeNode *node,
                                int leftmost)
{
    if (leftmost)
        self->leftmost = node;

    yume_rbtree_insert_color (&self->base, node);
}

void
yume_rbtree_cached_replace (YumeRBTreeCached *self, YumeRBTreeNode *victim,
                           YumeRBTreeNode *new)
{
    yume_rbtree_replace (&self->base, victim, new);

    if (self->leftmost == victim)
        self->leftmost = new;
}

void
yume_rbtree_cached_erase (YumeRBTreeCached *self, YumeRBTreeNode *node)
{
    if (node == self->leftmost)
        self->leftmost = yume_rbtree_node_next (node);

    yume_rbtree_erase (&self->base, node);
}
