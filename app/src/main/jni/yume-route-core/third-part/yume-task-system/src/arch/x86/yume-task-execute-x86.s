/*
 ============================================================================
 Name        : yume-task-execute-x86.s
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2018 everyone.
 Description :
 ============================================================================
 */

#include "asm.h"

NESTED(yume_task_execute)
    mov  %esp, %eax
    mov  0x4(%esp), %edx
    mov  0x8(%esp), %ecx
    mov  (%edx), %esp
    sub  $0x8, %esp
    push %eax

    push %edx
    call  *%ecx

    mov  0x4(%esp), %esp
    ret
END(yume_task_execute)
