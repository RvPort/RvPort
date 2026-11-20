"""Sample: ctypes/cffi native loading with an embedded C snippet."""

import ctypes
from ctypes import cdll
from cffi import FFI

# ctypes: qualified and from-import forms
simd = ctypes.CDLL("libsimd_x86.so")
aux = cdll.LoadLibrary("libaux_amd64.so")

# cffi: instance + embedded C declaration/source
ffi = FFI()
ffi.cdef("int simd_sum(const void *buf, int n);")
ffi.set_source(
    "_simd",
    "#include <immintrin.h>\n"
    "int simd_sum(const void *buf, int n) { __m128i v = _mm_add_epi32(v, v); return n; }",
)
runtime = ffi.dlopen("libz_x86_64.so")
