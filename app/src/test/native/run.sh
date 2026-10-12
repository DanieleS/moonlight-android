#!/bin/sh
# Builds and runs the host-side tests of the native code that parses network
# input, with the host compiler and AddressSanitizer/UBSan. CI runs this; so
# can anyone with a C++17 compiler: sh app/src/test/native/run.sh
set -eu

here=$(cd "$(dirname "$0")" && pwd)
jni="$here/../../main/jni"
out="${TMPDIR:-/tmp}/ratatoskr-native-tests"
mkdir -p "$out"

"${CXX:-c++}" -std=c++17 -O1 -g -Wall -Wextra -Wno-missing-field-initializers -Werror \
    -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer \
    -I"$jni/pyrowave-renderer" \
    "$here/pyrowaveframing_test.cpp" "$jni/pyrowave-renderer/pyrowaveframing.cpp" \
    -o "$out/pyrowaveframing_test"
"$out/pyrowaveframing_test"
