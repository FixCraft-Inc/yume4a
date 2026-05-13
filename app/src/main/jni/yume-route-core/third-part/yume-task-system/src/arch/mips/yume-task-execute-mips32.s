/*
 ============================================================================
 Name        : yume-task-execute-mips32.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

    .globl yume_task_execute
    .ent yume_task_execute, 0
    .type yume_task_execute, @function

yume_task_execute:
    lw  $t0, ($a0)
    sw  $sp, -0x4($t0)
    sw  $ra, -0x8($t0)
    addiu  $sp, $t0, -0x20

    move  $t9, $a1
    jalr  $a1

    lw  $ra, 0x18($sp)
    lw  $sp, 0x1c($sp)
    jr  $ra

    .end yume_task_execute
    .size yume_task_execute, . - yume_task_execute
