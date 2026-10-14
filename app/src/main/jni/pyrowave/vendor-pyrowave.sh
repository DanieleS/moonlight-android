#!/bin/sh
# Refreshes the vendored PyroWave codec in ./pyrowave from upstream.
#
# PyroWave's bitstream has no version field, so the client must decode with the
# exact codec the host encodes with. Vibepollo vendors Themaister/pyrowave at
# PYROWAVE_COMMIT with three local patches (none of them change the bitstream)
# and advertises the commit as PYROWAVE_BITSTREAM_ID in its RTSP DESCRIBE. This
# script builds the same tree: same pyrowave, Granite, volk and Vulkan-Headers
# commits as Vibepollo's third-party/pyrowave/VENDOR.txt, and patches/ copied
# verbatim from Vibepollo's third-party/pyrowave/patches/.
#
# Only the files the Android build compiles or includes are kept, plus the
# licences, the README and the bitstream spec; MANIFEST lists them. To refresh
# MANIFEST after a bump, build once with the whole tree and collect the
# dependency files (-MD) of the pyrowave module.
#
# Usage, from any directory (needs git and network access):
#
#   sh app/src/main/jni/pyrowave/vendor-pyrowave.sh [path to a Vibepollo checkout]
#
# With a Vibepollo checkout, the result is also compared file by file with
# Vibepollo's vendored tree, which must be identical except for KNOWN_DIFFS:
# Vibepollo edited Granite's DMA-BUF memory import by hand for its Linux host
# (described in its VENDOR.txt, not shipped as a patch). The decoder never
# imports external memory and the change does not touch the bitstream.
set -eu

PYROWAVE_COMMIT=186f0393b77f7755953b5ecde994bb1cec2e4155
GRANITE_COMMIT=b6cffd5ce81f540f0855e6778428483e14763d9b

KNOWN_DIFFS="Granite/vulkan/memory_allocator.cpp Granite/vulkan/vulkan_common.hpp"

HERE=$(cd "$(dirname "$0")" && pwd)
VIBEPOLLO=${1:-}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/pyrowave-vendor.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

checkout() {
    # $1: URL, $2: commit, $3: path
    git init -q "$3"
    git -C "$3" remote add origin "$1"
    git -C "$3" fetch -q --depth 1 origin "$2"
    git -C "$3" checkout -q --detach FETCH_HEAD
}

checkout https://github.com/Themaister/pyrowave "$PYROWAVE_COMMIT" "$WORK/pyrowave"
checkout https://github.com/Themaister/Granite "$GRANITE_COMMIT" "$WORK/Granite"
git -C "$WORK/Granite" submodule update -q --init --depth 1 third_party/volk third_party/khronos/vulkan-headers
VOLK_COMMIT=$(git -C "$WORK/Granite" rev-parse HEAD:third_party/volk)
HEADERS_COMMIT=$(git -C "$WORK/Granite" rev-parse HEAD:third_party/khronos/vulkan-headers)

# Assemble the tree the way upstream's checkout_granite.sh lays it out, outside
# any git work tree (git apply would otherwise resolve paths from its top).
STAGE="$WORK/stage"
mkdir -p "$STAGE"
cp -R "$WORK/pyrowave/." "$STAGE/"
rm -rf "$STAGE/.git" "$STAGE/Granite"
cp -R "$WORK/Granite" "$STAGE/Granite"
for patch in "$HERE"/patches/*.patch; do
    echo "Applying $(basename "$patch")"
    (cd "$STAGE" && git apply --whitespace=nowarn -p1 "$patch")
done

rm -rf "$HERE/pyrowave"
while IFS= read -r file; do
    [ -n "$file" ] || continue
    mkdir -p "$HERE/pyrowave/$(dirname "$file")"
    cp "$STAGE/$file" "$HERE/pyrowave/$file"
done < "$HERE/MANIFEST"

cat > "$HERE/VENDOR.txt" <<EOF
PyroWave codec, vendored by vendor-pyrowave.sh. Do not edit by hand; add a
patch under patches/ and rerun the script instead.

pyrowave:        $PYROWAVE_COMMIT (https://github.com/Themaister/pyrowave)
Granite:         $GRANITE_COMMIT (https://github.com/Themaister/Granite)
volk:            $VOLK_COMMIT
vulkan-headers:  $HEADERS_COMMIT

These are the commits Vibepollo vendors (third-party/pyrowave/VENDOR.txt), and
patches/ is a verbatim copy of Vibepollo's. The PyroWave bitstream carries no
version field, so host and client must be built from the same pyrowave commit;
Vibepollo advertises it in RTSP DESCRIBE as x-ss-pyrowave.bitstream, and
moonlight-common-c only negotiates PyroWave when it matches
PYROWAVE_BITSTREAM_ID (186f0393).

Vibepollo's copy differs only in Granite's DMA-BUF import (its Linux host
patch), which the decoder never uses.

Licences: pyrowave and Granite are MIT (pyrowave/LICENSE,
pyrowave/Granite/LICENSE), volk is MIT, Vulkan-Headers is Apache-2.0 OR MIT.
EOF

if [ -n "$VIBEPOLLO" ]; then
    status=0
    while IFS= read -r file; do
        [ -n "$file" ] || continue
        case " $KNOWN_DIFFS " in *" $file "*) continue ;; esac
        if ! cmp -s "$HERE/pyrowave/$file" "$VIBEPOLLO/third-party/pyrowave/pyrowave/$file"; then
            echo "Differs from Vibepollo: $file"
            status=1
        fi
    done < "$HERE/MANIFEST"
    [ $status -eq 0 ] && echo "Identical to Vibepollo's vendored tree (except $KNOWN_DIFFS)"
    exit $status
fi

echo "PyroWave vendored into $HERE/pyrowave"
