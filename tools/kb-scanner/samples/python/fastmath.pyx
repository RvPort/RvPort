# cython: language_level=3
# Cython sample: ctypes loading from a .pyx module
from ctypes import CDLL

def load():
    lib = ctypes.CDLL("libfast_x86.so")
    return lib
