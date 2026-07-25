#include "hud_snapshot.h"

#include <algorithm>

namespace hzzs::hud_v3 {

void HudSnapshotExchange::publish(const HudFrameSnapshot& input) noexcept {
    const std::uint8_t current = published_index_.load(std::memory_order_relaxed);
    const std::uint8_t next = static_cast<std::uint8_t>(current ^ 1u);
    Slot& slot = slots_[next];

    std::uint64_t sequence = slot.sequence.load(std::memory_order_relaxed);
    if ((sequence & 1u) != 0u) ++sequence;
    slot.sequence.store(sequence + 1u, std::memory_order_release);  // write in progress

    slot.snapshot = input;
    slot.snapshot.detection_count = static_cast<std::uint8_t>(
        std::min<std::size_t>(slot.snapshot.detection_count, kMaxHudDetections));

    slot.sequence.store(sequence + 2u, std::memory_order_release);
    published_index_.store(next, std::memory_order_release);
    has_value_.store(true, std::memory_order_release);
}

bool HudSnapshotExchange::try_read(HudFrameSnapshot& output) const noexcept {
    if (!has_value_.load(std::memory_order_acquire)) return false;

    for (int attempt = 0; attempt < 4; ++attempt) {
        const std::uint8_t index = published_index_.load(std::memory_order_acquire);
        const Slot& slot = slots_[index];
        const std::uint64_t before = slot.sequence.load(std::memory_order_acquire);
        if ((before & 1u) != 0u) continue;
        const HudFrameSnapshot candidate = slot.snapshot;
        std::atomic_thread_fence(std::memory_order_acquire);
        const std::uint64_t after = slot.sequence.load(std::memory_order_acquire);
        if (before == after && (after & 1u) == 0u) {
            output = candidate;
            return true;
        }
    }
    return false;
}

}  // namespace hzzs::hud_v3
