#include <arm_neon.h>
#ifdef __ARM_NEON
static inline void vec_add(int32x4_t *a, int32x4_t *b) {
    *a = vaddq_s32(*a, *b);
}
#endif

unsigned long read_fpcr(void) {
    unsigned long value;
    __asm__ volatile("mrs %0, fpcr" : "=r"(value));
    return value;
}

void set_fpsr(unsigned long v) {
    __builtin_aarch64_set_fpsr(v);
}
