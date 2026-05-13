/*
 ============================================================================
 Name        : yume-task-execute-sw64.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2018 everyone.
 Description :
 ============================================================================
 */

#include <asm/regdef.h>

    .globl yume_task_execute
    .ent yume_task_execute, 0
    .type yume_task_execute, @function

yume_task_execute:
    ldl  t0, 0(a0)
    stl  sp, -0x08(t0)
    stl  ra, -0x10(t0)
    subl  t0, 0x10, sp

    mov  a1, pv
    call  ra, (pv)

    ldl  ra, 0(sp)
    ldl  sp, 8(sp)
    ret  zero, (ra)

    .end yume_task_execute
    .size yume_task_execute, . - yume_task_execute
