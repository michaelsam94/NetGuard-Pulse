#include "session_shaper.h"

#include <algorithm>
#include <cmath>

SessionShaper::SessionShaper(int64_t rate_bytes_per_sec)
    : rate_bytes_per_sec_(std::max<int64_t>(rate_bytes_per_sec, 1)),
      tokens_(static_cast<double>(rate_bytes_per_sec_)),
      capacity_(static_cast<double>(std::min<int64_t>(rate_bytes_per_sec_ / 2, 64 * 1024))),
      last_ns_(0) {}

void SessionShaper::setRate(int64_t rate_bytes_per_sec) {
    rate_bytes_per_sec_ = std::max<int64_t>(rate_bytes_per_sec, 1);
    capacity_ = static_cast<double>(std::min<int64_t>(rate_bytes_per_sec_ / 2, 64 * 1024));
    tokens_ = std::min(tokens_, capacity_);
}

void SessionShaper::refill(int64_t now_ns) {
    if (last_ns_ <= 0) {
        last_ns_ = now_ns;
        return;
    }
    const double elapsed_sec = static_cast<double>(now_ns - last_ns_) / 1e9;
    if (elapsed_sec > 0.0) {
        tokens_ = std::min(capacity_, tokens_ + static_cast<double>(rate_bytes_per_sec_) * elapsed_sec);
        last_ns_ = now_ns;
    }
}

ShaperResult SessionShaper::onTraffic(int64_t bytes, int64_t now_ns) {
    refill(now_ns);
    if (bytes <= 0) {
        return {false, 0, 0};
    }

    tokens_ -= static_cast<double>(bytes);
    if (tokens_ >= 0.0) {
        return {false, 0, 0};
    }

    const int64_t debt = static_cast<int64_t>(std::ceil(-tokens_));
    tokens_ = 0.0;

    int64_t pause_ms = (debt * 1000) / rate_bytes_per_sec_;
    pause_ms = std::clamp(pause_ms, static_cast<int64_t>(100), static_cast<int64_t>(10000));

    return {true, pause_ms, debt};
}
