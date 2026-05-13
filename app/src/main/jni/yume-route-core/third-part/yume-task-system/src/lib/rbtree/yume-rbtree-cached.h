/*
 ============================================================================
 Name        : yume-rbtree-cached.h
 Authors     : Andrea Arcangeli <andrea@suse.de>
               David Woodhouse <dwmw2@infradead.org>
               Michel Lespinasse <walken@google.com>
               Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description : RedBlack Tree Cached
 ============================================================================
 */

#ifndef __YUME_RBTREE_CACHED_H__
#define __YUME_RBTREE_CACHED_H__

#include "yume-rbtree.h"

typedef struct _YumeRBTreeCached YumeRBTreeCached;

/*
 * Leftmost-cached rbtrees.
 *
 * We do not cache the rightmost node based on footprint
 * size vs number of potential users that could benefit
 * from O(1) rb_last(). Just not worth it, users that want
 * this feature can always implement the logic explicitly.
 * Furthermore, users that want to cache both pointers may
 * find it a bit asymmetric, but that's ok.
 */
struct _YumeRBTreeCached
{
    YumeRBTree base;
    YumeRBTreeNode *leftmost;
};

static inline YumeRBTreeNode *
yume_rbtree_cached_first (YumeRBTreeCached *self)
{
    return self->leftmost;
}

void yume_rbtree_cached_insert_color (YumeRBTreeCached *self, YumeRBTreeNode *node,
                                     int leftmost);

void yume_rbtree_cached_replace (YumeRBTreeCached *self, YumeRBTreeNode *victim,
                                YumeRBTreeNode *new);

void yume_rbtree_cached_erase (YumeRBTreeCached *self, YumeRBTreeNode *node);

#endif /* __YUME_RBTREE_CACHED_H__ */
