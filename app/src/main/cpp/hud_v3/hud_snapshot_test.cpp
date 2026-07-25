#include "hud_snapshot.h"

#include <atomic>
#include <cassert>
#include <thread>

namespace {

using hzzs::hud_v3::HudFrameSnapshot;
using hzzs::hud_v3::HudSnapshotExchange;

void test_empty_exchange() {
    HudSnapshotExchange exchange;
    HudFrameSnapshot output{};
    assert(!exchange.try_read(output));
}

void test_publish_and_clamp() {
    HudSnapshotExchange exchange;
    HudFrameSnapshot input{};
    input.generation = 42;
    input.detection_count = 255;
    exchange.publish(input);
    HudFrameSnapshot output{};
    assert(exchange.try_read(output));
    assert(output.generation == 42);
    assert(output.detection_count == 32);
}

void test_concurrent_snapshots_are_not_torn() {
    HudSnapshotExchange exchange;
    std::atomic<bool> done{false};
    std::thread writer([&] {
        for (std::uint64_t generation = 1; generation <= 20000; ++generation) {
            HudFrameSnapshot frame{};
            frame.generation = generation;
            frame.algorithm_micros = static_cast<std::uint32_t>(generation & 0xffffffffu);
            frame.detection_count = 1;
            frame.detections[0].confidence = static_cast<float>(generation);
            exchange.publish(frame);
        }
        done.store(true, std::memory_order_release);
    });

    std::uint64_t last = 0;
    while (!done.load(std::memory_order_acquire)) {
        HudFrameSnapshot frame{};
        if (!exchange.try_read(frame)) continue;
        assert(frame.generation >= last);
        assert(frame.algorithm_micros == static_cast<std::uint32_t>(frame.generation));
        assert(frame.detections[0].confidence == static_cast<float>(frame.generation));
        last = frame.generation;
    }
    writer.join();
}

}  // namespace

int main() {
    test_empty_exchange();
    test_publish_and_clamp();
    test_concurrent_snapshots_are_not_torn();
    return 0;
}
