// Host-side tests for the PyroWave framing parser
// (app/src/main/jni/pyrowave-renderer/pyrowaveframing.cpp), which reads
// untrusted bytes from the network before anything reaches the decoder.
// Built and run by run.sh with the host compiler and sanitizers; CI runs it.

#include "pyrowaveframing.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

using namespace PyroWaveFraming;

namespace {

int g_Failures = 0;

#define CHECK(cond) \
    do { \
        if (!(cond)) { \
            fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            g_Failures++; \
        } \
    } while (0)

constexpr uint32_t k_Seq = 3;
constexpr size_t k_RecordWords = 10;
const StreamGeometry k_1080p = {1920, 1080, false};

void put(std::vector<uint8_t>& v, uint32_t word)
{
    for (int i = 0; i < 4; i++) {
        v.push_back(uint8_t(word >> (8 * i)));
    }
}

void putSequenceHeader(std::vector<uint8_t>& v, const StreamGeometry& g, uint32_t totalBlocks)
{
    put(v, 0x80000000u | uint32_t(g.width - 1) | (uint32_t(g.height - 1) << 14) | (k_Seq << 28));
    put(v, totalBlocks | (g.chroma444 ? (1u << 26) : 0u));
}

void putBlock(std::vector<uint8_t>& v, uint32_t index, uint32_t words = k_RecordWords)
{
    put(v, (words << 16) | (k_Seq << 28));
    put(v, index << 8);
    for (uint32_t k = 2; k < words; k++) {
        put(v, 0x01010101u * (index + k));
    }
}

void putPadding(std::vector<uint8_t>& v, uint32_t words)
{
    put(v, k_PaddingMagic);
    put(v, words);
    for (uint32_t k = 0; k < words; k++) {
        put(v, 0);
    }
}

// A record-framed frame packed as Vibepollo packs it: no record crosses a
// packet boundary, and padding fills the end of a packet. The first packet
// carries 8 fewer frame bytes (the frame header). Every packet starts with a
// record, so every segment is flagged.
struct PackedFrame {
    std::vector<uint8_t> data;
    std::vector<Segment> segments;
};

PackedFrame packFrame(uint32_t blocks, size_t packetPayload = 1376)
{
    PackedFrame f;
    const size_t recordBytes = k_RecordWords * 4;
    size_t packetStart = 0;
    size_t capacity = packetPayload - 8;
    auto closePacket = [&]() {
        f.segments.push_back({packetStart, f.data.size() - packetStart, false, true});
        packetStart = f.data.size();
        capacity = packetPayload;
    };

    putSequenceHeader(f.data, k_1080p, blocks);
    for (uint32_t i = 0; i < blocks; i++) {
        const size_t used = f.data.size() - packetStart;
        if (used + recordBytes > capacity) {
            const size_t left = capacity - used;
            if (left >= 8) {
                putPadding(f.data, uint32_t((left - 8) / 4));
            }
            closePacket();
        }
        putBlock(f.data, i);
    }
    closePacket();
    return f;
}

void loseSegment(PackedFrame& f, size_t index)
{
    f.segments[index].lost = true;
    memset(f.data.data() + f.segments[index].offset, 0, f.segments[index].size);
}

// What every successful parse must hold, whatever the input
void checkSpans(const Frame& frame, size_t size)
{
    size_t end = 0;
    for (const auto& span : frame.spans) {
        CHECK(span.size > 0);
        CHECK(span.offset >= end);
        CHECK(span.offset + span.size <= size);
        end = span.offset + span.size;
    }
}

void testIntactFrame()
{
    PackedFrame f = packFrame(200);
    Frame frame;
    std::string error;

    // Whole, without a packet map
    CHECK(parse(f.data.data(), f.data.size(), k_1080p, frame, error));
    CHECK(frame.framing == Framing::Records);
    CHECK(frame.blockRecords == 200);
    CHECK(frame.announcedBlocks == 200);
    CHECK(!frame.partial);
    CHECK(frame.paddingBytes > 0);
    checkSpans(frame, f.data.size());

    // With the packet map
    CHECK(parse(f.data.data(), f.data.size(), f.segments, k_1080p, frame, error));
    CHECK(frame.blockRecords == 200);
    CHECK(!frame.partial);
    CHECK(frame.coarseLevelIntact);
}

void testLostFinerPacket()
{
    PackedFrame f = packFrame(200);
    CHECK(f.segments.size() > 4);
    // 1080p 4:2:0 has 48 coarsest-level blocks, all in the first two packets
    CHECK(coarseBlockCount(k_1080p) == 48);
    const size_t blocksInLost = f.segments[3].size / (k_RecordWords * 4);
    loseSegment(f, 3);

    Frame frame;
    std::string error;
    CHECK(parse(f.data.data(), f.data.size(), f.segments, 2, k_1080p, frame, error));
    CHECK(frame.partial);
    CHECK(frame.coarseLevelIntact);
    CHECK(frame.blockRecords == 200 - blocksInLost);
    checkSpans(frame, f.data.size());
    for (const auto& span : frame.spans) {
        // Nothing from the lost packet reaches the decoder
        const size_t lostStart = f.segments[3].offset;
        const size_t lostEnd = lostStart + f.segments[3].size;
        CHECK(span.offset + span.size <= lostStart || span.offset >= lostEnd);
    }
}

void testLostCoarsePacket()
{
    PackedFrame f = packFrame(200);
    loseSegment(f, 1);
    Frame frame;
    std::string error;

    // Announced by the host
    CHECK(parse(f.data.data(), f.data.size(), f.segments, 2, k_1080p, frame, error));
    CHECK(frame.partial);
    CHECK(!frame.coarseLevelIntact);

    // Not announced: inferred from where the loss is
    CHECK(parse(f.data.data(), f.data.size(), f.segments, 0, k_1080p, frame, error));
    CHECK(frame.partial);
    CHECK(!frame.coarseLevelIntact);
}

void testLostFirstPacket()
{
    PackedFrame f = packFrame(200);
    loseSegment(f, 0);
    Frame frame;
    std::string error;
    CHECK(!parse(f.data.data(), f.data.size(), f.segments, k_1080p, frame, error));
}

void testWrongGeometry()
{
    PackedFrame f = packFrame(20);
    Frame frame;
    std::string error;
    CHECK(!parse(f.data.data(), f.data.size(), StreamGeometry{1280, 720, false}, frame, error));
    CHECK(!parse(f.data.data(), f.data.size(), StreamGeometry{1920, 1080, true}, frame, error));
}

void testMalformedRecords()
{
    Frame frame;
    std::string error;

    // Truncated: the last record runs past the end of the frame
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 2);
        putBlock(v, 0);
        putBlock(v, 1);
        v.resize(v.size() - 8);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // A record that claims more words than the frame holds
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 1);
        put(v, (0xFFFu << 16) | (k_Seq << 28));
        put(v, 0);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // A record shorter than its own header
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 1);
        putBlock(v, 0, 1);
        put(v, 0);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // A block index the frame cannot hold
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 1);
        putBlock(v, maxBlockCount(k_1080p));
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // More blocks announced than the frame can hold
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, maxBlockCount(k_1080p) + 1);
        putBlock(v, 0);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // Padding that runs past the end
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 1);
        putBlock(v, 0);
        put(v, k_PaddingMagic);
        put(v, 1000);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // Fewer blocks than announced, nothing lost
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 3);
        putBlock(v, 0);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // A block from another frame's sequence
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 1);
        put(v, (uint32_t(k_RecordWords) << 16) | (((k_Seq + 1) & 7) << 28));
        put(v, 0);
        for (size_t k = 2; k < k_RecordWords; k++) {
            put(v, 0);
        }
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
    }
    // Not word aligned, and empty
    {
        std::vector<uint8_t> v;
        putSequenceHeader(v, k_1080p, 0);
        v.push_back(0);
        CHECK(!parse(v.data(), v.size(), k_1080p, frame, error));
        CHECK(!parse(v.data(), 0, k_1080p, frame, error));
        CHECK(!parse(nullptr, 8, k_1080p, frame, error));
    }
    // A packet map that does not tile the frame
    {
        PackedFrame f = packFrame(20);
        f.segments.back().size -= 4;
        CHECK(!parse(f.data.data(), f.data.size(), f.segments, k_1080p, frame, error));
    }
}

std::vector<uint8_t> lengthPrefixedFrame(uint32_t blocks)
{
    std::vector<uint8_t> v;
    put(v, 1);
    put(v, uint32_t(8 + blocks * k_RecordWords * 4));
    putSequenceHeader(v, k_1080p, blocks);
    for (uint32_t i = 0; i < blocks; i++) {
        putBlock(v, i);
    }
    return v;
}

void testLengthPrefixed()
{
    Frame frame;
    std::string error;

    std::vector<uint8_t> v = lengthPrefixedFrame(5);
    CHECK(parse(v.data(), v.size(), k_1080p, frame, error));
    CHECK(frame.framing == Framing::LengthPrefixed);
    CHECK(frame.blockRecords == 5);
    checkSpans(frame, v.size());

    // Any loss: the packet lengths cannot be trusted
    std::vector<Segment> segments = {{0, 16, false}, {16, v.size() - 16, true}};
    CHECK(!parse(v.data(), v.size(), segments, k_1080p, frame, error));

    // A packet length past the end
    std::vector<uint8_t> bad = v;
    bad[4] = 0xFC;
    bad[5] = 0xFF;
    CHECK(!parse(bad.data(), bad.size(), k_1080p, frame, error));

    // A packet count the frame cannot hold
    bad = v;
    bad[0] = 0xFF;
    bad[1] = 0xFF;
    CHECK(!parse(bad.data(), bad.size(), k_1080p, frame, error));

    // Bytes after the last packet
    bad = v;
    put(bad, 0);
    CHECK(!parse(bad.data(), bad.size(), k_1080p, frame, error));
}

// Random corruption of valid frames and packet maps: nothing may read out of
// bounds (the sanitizers catch that) and every accepted frame's spans stay
// inside it.
void testRandomInput()
{
    std::mt19937 rng(0x5079726fu);
    const PackedFrame base = packFrame(300);
    const std::vector<uint8_t> lp = lengthPrefixedFrame(40);

    for (int iteration = 0; iteration < 20000; iteration++) {
        PackedFrame f = base;
        if (iteration % 4 == 3) {
            f.data = lp;
            f.segments = {{0, f.data.size(), false, true}};
        }

        const int mutations = int(rng() % 8);
        for (int m = 0; m < mutations; m++) {
            const size_t at = rng() % f.data.size();
            switch (rng() % 3) {
            case 0: f.data[at] = uint8_t(rng()); break;
            case 1: f.data[at] ^= uint8_t(1u << (rng() % 8)); break;
            default: {
                const size_t word = at & ~size_t(3);
                const uint32_t values[] = {0u, 0xFFFFFFFFu, 0x80000000u, 0x0FFF0000u, uint32_t(rng())};
                const uint32_t value = values[rng() % 5];
                memcpy(f.data.data() + word, &value, 4);
                break;
            }
            }
        }
        for (auto& segment : f.segments) {
            if (rng() % 6 == 0) {
                segment.lost = true;
                memset(f.data.data() + segment.offset, 0, segment.size);
            }
            if (rng() % 8 == 0) {
                segment.recordStart = !segment.recordStart;
            }
        }

        Frame frame;
        std::string error;
        const size_t critical = rng() % 4;
        bool ok;
        if (rng() % 5 == 0) {
            // Truncated to a random word count, packet boundaries unknown
            const size_t size = (rng() % (f.data.size() / 4 + 1)) * 4;
            ok = parse(f.data.data(), size, k_1080p, frame, error);
            if (ok) {
                checkSpans(frame, size);
            }
        }
        else {
            ok = parse(f.data.data(), f.data.size(), f.segments, critical, k_1080p, frame, error);
            if (ok) {
                checkSpans(frame, f.data.size());
            }
        }
        if (!ok) {
            CHECK(!error.empty());
        }
    }
}

} // namespace

int main()
{
    testIntactFrame();
    testLostFinerPacket();
    testLostCoarsePacket();
    testLostFirstPacket();
    testWrongGeometry();
    testMalformedRecords();
    testLengthPrefixed();
    testRandomInput();

    if (g_Failures != 0) {
        fprintf(stderr, "%d check(s) failed\n", g_Failures);
        return 1;
    }
    printf("pyrowaveframing: all tests passed\n");
    return 0;
}
