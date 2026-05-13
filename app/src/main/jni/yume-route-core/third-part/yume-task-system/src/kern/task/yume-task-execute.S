/*
 ============================================================================
 Name        : yume-task-execute.S
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

#if defined(__i386__)

# include "arch/x86/yume-task-execute-x86.s"

#elif defined(__x86_64__)

# include "arch/x86/yume-task-execute-x86_64.s"

#elif defined(__mips__)

# if (_MIPS_SIM == _ABI64)
#  include "arch/mips/yume-task-execute-mips64.s"
# else
#  include "arch/mips/yume-task-execute-mips32.s"
# endif

#elif defined(__arm__)

# include "arch/arm/yume-task-execute-arm.s"

#elif defined(__aarch64__)

# include "arch/arm/yume-task-execute-aarch64.s"

#elif defined(__sw_64__)

# include "arch/sw64/yume-task-execute-sw64.s"

#elif defined(__riscv)

# if defined(_LP64)
#  include "arch/riscv/yume-task-execute-riscv64.s"
# else
#  include "arch/riscv/yume-task-execute-riscv32.s"
# endif

#elif defined(__loongarch__)

# if defined(_LP64)
#  include "arch/loong/yume-task-execute-la64.s"
# else
#  include "arch/loong/yume-task-execute-la32.s"
# endif

#elif defined(__powerpc64__)

# include "arch/ppc/yume-task-execute-ppc64.s"

#elif defined(__powerpc__)

# include "arch/ppc/yume-task-execute-ppc32.s"

#elif defined(__arc__)

# include "arch/arc/yume-task-execute-arc32.s"

#elif defined(__m68k__)

# include "arch/m68k/yume-task-execute-m68k.s"

#elif defined(__microblaze__)

# include "arch/microblaze/yume-task-execute-microblaze.s"

#elif defined(__s390x__)

# include "arch/s390/yume-task-execute-s390x.s"

#elif defined(__sh__)

# include "arch/sh/yume-task-execute-sh.s"

#elif defined(__or1k__)

# include "arch/openrisc/yume-task-execute-or1k.s"

#else

# error "Unsupported platform!"

#endif

#if defined(__ELF__) && defined(__linux__)
/* An executable stack is *not* required for these functions. */
    .section .note.GNU-stack,"",%progbits
    .previous
#endif
