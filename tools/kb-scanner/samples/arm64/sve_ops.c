#include <arm_sve.h>
#include <arm_acle.h>

#ifdef __ARM_FEATURE_SVE
svint32_t widen(svint16_t x) { return svunpk_s32(x); }
#endif

int crc32b(unsigned d) { return __crc32b(0, d); }
