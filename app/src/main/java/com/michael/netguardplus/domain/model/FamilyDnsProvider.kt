package com.michael.netguardplus.domain.model

enum class FamilyDnsProvider(
    val label: String,
    val description: String,
    val primaryIp: String,
    val secondaryIp: String
) {
    SYSTEM_DEFAULT(
        label = "System Default",
        description = "Use your network's DNS (no family filtering from DNS)",
        primaryIp = "",
        secondaryIp = ""
    ),
    CLOUDFLARE_FAMILY(
        label = "Cloudflare Family",
        description = "Malware blocking + local adult/gambling filter (1.1.1.2)",
        primaryIp = "1.1.1.2",
        secondaryIp = "1.0.0.2"
    ),
    CLOUDFLARE_SECURITY(
        label = "Cloudflare Security",
        description = "Blocks malware only (1.1.1.3)",
        primaryIp = "1.1.1.3",
        secondaryIp = "1.0.0.3"
    ),
    OPENDNS_FAMILY(
        label = "OpenDNS FamilyShield",
        description = "Strong adult blocking at DNS + local filter (208.67.222.123)",
        primaryIp = "208.67.222.123",
        secondaryIp = "208.67.220.123"
    ),
    CLEANBROWSING_FAMILY(
        label = "CleanBrowsing Family",
        description = "Adult blocking at DNS + local filter (185.228.168.168)",
        primaryIp = "185.228.168.168",
        secondaryIp = "185.228.169.168"
    ),
    CLEANBROWSING_ADULT(
        label = "CleanBrowsing Adult Filter",
        description = "Strictest adult blocking + local filter (185.228.168.10)",
        primaryIp = "185.228.168.10",
        secondaryIp = "185.228.169.11"
    ),
    ADGUARD_FAMILY(
        label = "AdGuard Family",
        description = "Blocks ads, trackers, and adult content",
        primaryIp = "94.140.14.14",
        secondaryIp = "94.140.15.15"
    ),
    QUAD9(
        label = "Quad9 Secure",
        description = "Blocks malware and phishing (9.9.9.9)",
        primaryIp = "9.9.9.9",
        secondaryIp = "149.112.112.112"
    );

    val serverIps: List<String>
        get() = buildList {
            if (primaryIp.isNotBlank()) add(primaryIp)
            if (secondaryIp.isNotBlank()) add(secondaryIp)
        }
}
