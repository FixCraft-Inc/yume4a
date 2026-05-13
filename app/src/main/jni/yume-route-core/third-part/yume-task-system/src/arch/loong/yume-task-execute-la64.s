/*
 ============================================================================
 Name        : yume-task-execute-la64.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2020 everyone.
 Description :
 ============================================================================
 */

    .globl yume_task_execute
    .type yume_task_execute, @function

yume_task_execute:
    ld.d  $t0, $a0, 0
    st.d  $sp, $t0, -8
    st.d  $ra, $t0, -16
    addi.d  $sp, $t0, -16

    jirl  $ra, $a1, 0

    ld.d  $ra, $sp, 0
    ld.d  $sp, $sp, 8
    jirl  $zero, $ra, 0

    .size yume_task_execute, . - yume_task_execute
