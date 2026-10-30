# Dual-architecture build and test examples

English | [简体中文](build.zh-CN.md)

Commands assume that RvPort has deployed the source into the current working directory. Unless stated otherwise, execute the same commands natively in both the x86_64 source environment and riscv64 target environment. Do not substitute x86 artifacts for a RISC-V build. These are commands for the configured execution environments, not instructions to install dependencies on the development host.

## Example selection and prerequisites

| Difficulty                | Repository                                             | Purpose                                                   | Basic environment dependencies                                                      |
| ------------------------- | ------------------------------------------------------ | --------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| Simple                    | [simdinfo](https://github.com/abetlen/simdinfo)        | x86 SIMD detection and RISC-V adaptation branches         | `gcc`, `make`                                                                       |
| Simple                    | [cJSON](https://github.com/DaveGamble/cJSON)           | ANSI C library and unit tests                             | `gcc`, `make`, `cmake`                                                              |
| Medium                    | [zlib](https://github.com/madler/zlib)                 | Compression library and CMake/CTest                       | `gcc`, `make`, `cmake`                                                              |
| Complex                   | [SQLite](https://github.com/sqlite/sqlite)             | Code generation, CLI, full regression suite               | `gcc`, `make`; full tests also require `tcl`, `tcl-devel`                           |
| Complex                   | [libarchive](https://github.com/libarchive/libarchive) | Multiple archive formats, feature detection, CLI, tests   | `gcc`, `make`, `cmake`; full features require compression-format `*-devel` packages |
| High complexity, optional | [curl](https://github.com/curl/curl)                   | Optional protocol, TLS, compression, and SSH dependencies | `gcc`, `make`, `autoconf`, `automake`, `libtool`, `pkgconf-pkg-config`, `perl`      |

Start with simdinfo to verify that each architecture takes its intended compilation branch. Then get cJSON and zlib working, followed by SQLite and libarchive for more complex build/test flows. Import curl after those examples are stable.

## simdinfo: build and architecture validation

Source: https://github.com/abetlen/simdinfo

simdinfo is an architecture-migration example rather than a general cross-architecture library. The x86 target uses its supplied Makefile; the RISC-V target uses an explicit generic compilation path. Upstream has no directly callable automated test target, so successful compilation plus execution of the matching architecture's binary is recorded as smoke validation.

### Build script for both environments

```shell
set -e
mkdir -p bin

case "$(uname -m)" in
  x86_64)
    make clean
    make bin/main-x86_64
    ;;
  riscv64)
    gcc \
      -o bin/main-riscv64 \
      main.c \
      -march=rv64gc \
      -mabi=lp64d \
      -I. \
      -O2 \
      -Wall
    ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac
```

### Test/smoke script

```shell
set -eu

case "$(uname -m)" in
  x86_64)
    test -x bin/main-x86_64
    bin/main-x86_64
    ;;
  riscv64)
    test -x bin/main-riscv64
    bin/main-riscv64
    ;;
  *)
    echo "Unsupported architecture: $(uname -m)" >&2
    exit 1
    ;;
esac
```

## cJSON: simple build

Source: https://github.com/DaveGamble/cJSON

### Build script for both environments

```shell
set -eu

cmake -S . -B build \
  -DENABLE_CJSON_TEST=ON \
  -DENABLE_CJSON_UTILS=ON
cmake --build build --parallel 2
```

### Test script

```shell
set -eu

ctest --test-dir build --output-on-failure
```

## zlib: medium-complexity build

Source: https://github.com/madler/zlib

### Build script for both environments

```shell
set -eu

cmake -S . -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DZLIB_BUILD_TESTING=ON
cmake --build build --parallel 2
```

### Test script

```shell
set -eu

ctest --test-dir build --output-on-failure
```

## SQLite: complex build

Source: https://github.com/sqlite/sqlite

SQLite's `sqlite3` CLI can be built with a C toolchain alone, while the full regression suite requires Tcl. Record basic build validation separately from full tests.

### Build script for both environments

```shell
set -eu

mkdir -p build
cd build
../configure
make -j"$(nproc)" sqlite3
./sqlite3 --version
```

### Full tests: requires `tcl` and `tcl-devel`

```shell
set -eu

cd build
make -j"$(nproc)" test
```

### Basic validation without Tcl

```shell
set -eu

cd build
./sqlite3 ':memory:' 'select sqlite_version();'
```

## libarchive: complex build

Source: https://github.com/libarchive/libarchive

The repository detects compression, encryption, ACL, xattr, and other environment capabilities automatically. Retain default detection results for the first validation. Missing optional `*-devel` packages generally reduce corresponding features rather than necessarily failing the build.

### Build in both environments

```shell
set -eu

cmake -S . -B build \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_TEST=ON
cmake --build build --parallel 2
```

### Test in both environments

```shell
set -eu

ctest --test-dir build --output-on-failure
```

The build enables and compiles test targets; the test command then runs only `ctest` in that build directory.

## curl: optional high-complexity build

Source: https://github.com/curl/curl

curl exercises dependency discovery and feature selection. Start with TLS, compression, LDAP, and other optional features disabled for a minimal reproducible build on both architectures. Add development packages and enable features individually afterwards.

### Minimal build script for both environments

```shell
set -eu

./buildconf
./configure \
  --without-ssl \
  --without-libpsl \
  --without-zlib \
  --without-brotli \
  --without-zstd \
  --disable-ldap \
  --disable-ldaps
make -j"$(nproc)"
```

### Test script: requires `perl`

```shell
set -eu

make test
```

### Minimal-build smoke check

```shell
set -eu

./src/curl --version
```
