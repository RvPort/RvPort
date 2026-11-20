//go:build linux && amd64

package main

/*
#include <immintrin.h>

__m256i zero_block(void) {
    return _mm256_setzero_si256();
}
*/
import "C"

import "runtime"

// Sum picks an architecture path at runtime.
func Sum(xs []int32) int32 {
	var acc int32
	if runtime.GOARCH == "amd64" {
		v := C.zero_block()
		_ = v
		for _, x := range xs {
			acc += x
		}
		return acc
	}
	// Portable fallback (riscv64 and others).
	for _, x := range xs {
		acc += x
	}
	return acc
}
