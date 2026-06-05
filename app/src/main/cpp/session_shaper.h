#ifndef NETGUARD_SESSION_SHAPER_H
#define NETGUARD_SESSION_SHAPER_H

#include <cstdint>

struct ShaperResult {
    bool should_pause;
    int64_t pause_ms;
    int64_t debt_bytes;
};

class SessionShaper {
public:
    explicit SessionShaper(int64_t rate_bytes_per_sec);

    ShaperResult onTraffic(int64_t bytes, int64_t now_ns);

    void setRate(int64_t rate_bytes_per_sec);

private:
    void refill(int64_t now_ns);

    int64_t rate_bytes_per_sec_;
    double tokens_;
    double capacity_;
    int64_t last_ns_;
};

#endif
