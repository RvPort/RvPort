//! Sample: x86 SSE4 intrinsic under a cfg guard, with a RISC-V alternative branch.

use core::arch::x86_64::*;

#[cfg(target_arch = "x86_64")]
pub fn crc32(data: &[u8]) -> u32 {
    let mut acc: u32 = 0;
    unsafe {
        let v: __m128i = _mm_add_epi32(data[0] as i32, data[1] as i32);
        acc = acc.wrapping_add(v as u32);
        acc = _mm_crc32_u32(acc, data[2] as u32);
    }
    acc
}

#[cfg(target_arch = "riscv64")]
pub fn crc32(data: &[u8]) -> u32 {
    // Portable fallback loop for the RISC-V build.
    data.iter().fold(0u32, |acc, b| acc.wrapping_add(*b as u32))
}

#[cfg(unix)]
fn platform_note() {}

unsafe fn raw_jump() {
    global_asm!("movaps %xmm0, %xmm1");
}
