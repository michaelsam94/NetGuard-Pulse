package com.michael.netguardplus.domain.model

enum class UsageCategory {
    MOBILE,
    WIFI,
    HOTSPOT,
    ALL
}

enum class HistoryDateRange {
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    CUSTOM
}

data class UsageTraffic(
    val rxBytes: Long = 0L,
    val txBytes: Long = 0L
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class UsageHistorySummary(
    val mobile: UsageTraffic = UsageTraffic(),
    val wifi: UsageTraffic = UsageTraffic(),
    val hotspot: UsageTraffic = UsageTraffic()
) {
    val grandTotal: Long get() = mobile.totalBytes + wifi.totalBytes + hotspot.totalBytes
}

data class UsageHistoryBucket(
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val mobile: UsageTraffic = UsageTraffic(),
    val wifi: UsageTraffic = UsageTraffic(),
    val hotspot: UsageTraffic = UsageTraffic()
) {
    fun totalFor(category: UsageCategory): UsageTraffic {
        return when (category) {
            UsageCategory.MOBILE -> mobile
            UsageCategory.WIFI -> wifi
            UsageCategory.HOTSPOT -> hotspot
            UsageCategory.ALL -> UsageTraffic(
                rxBytes = mobile.rxBytes + wifi.rxBytes + hotspot.rxBytes,
                txBytes = mobile.txBytes + wifi.txBytes + hotspot.txBytes
            )
        }
    }
}

data class UsageHistoryReport(
    val summary: UsageHistorySummary,
    val buckets: List<UsageHistoryBucket>,
    val startMs: Long,
    val endMs: Long
)
