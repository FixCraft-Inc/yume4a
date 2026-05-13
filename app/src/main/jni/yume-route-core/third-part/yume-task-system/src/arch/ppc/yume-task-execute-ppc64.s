/*
 ============================================================================
 Name        : yume-task-execute-ppc64.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2022 everyone.
 Description :
 ============================================================================
 */

    .globl yume_task_execute
    .type yume_task_execute, @function

yume_task_execute:
    ld    5, 0(3)
    mflr  0
    std   1, -0x08(5)
    std   0, -0x10(5)
    addi  1, 5, -0x40

    mtctr 4
    bctrl

    ld    0, 0x30(1)
    ld    1, 0x38(1)
    mtlr  0
    blr

    .size yume_task_execute, . - yume_task_execute
