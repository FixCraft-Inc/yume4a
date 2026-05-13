/*
 ============================================================================
 Name        : yume-task-execute-sh.s
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2024 everyone.
 Description :
 ============================================================================
 */

    .globl yume_task_execute
    .type yume_task_execute, @function

yume_task_execute:
    mov.l @r4, r3
    mov.l r15, @-r3
    sts.l pr, @-r3

    jsr   @r5
    mov   r3, r15

    lds.l @r15+, pr
    rts
    mov.l @r15, r15

    .end yume_task_execute
    .size yume_task_execute, . - yume_task_execute
