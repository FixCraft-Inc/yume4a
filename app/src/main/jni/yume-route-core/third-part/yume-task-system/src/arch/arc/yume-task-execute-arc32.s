/*
 ============================================================================
 Name        : yume-task-execute-arc32.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2022 everyone.
 Description :
 ============================================================================
 */

    .globl yume_task_execute
    .type yume_task_execute, @function

yume_task_execute:
    ld   r2, [r0]
    st   sp, [r2, -0x0c]
    st   blink, [r2, -0x10]
    add  sp, r2, -0x10

    jl_s [r1]

    ld   blink, [sp]
    ld   sp, [sp, 0x0c]
    j_s  [blink]

    .size yume_task_execute, . - yume_task_execute
