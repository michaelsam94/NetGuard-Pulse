package com.michael.netguardplus.domain.model

enum class ParentalCategory(
    val label: String,
    val description: String
) {
    ADULT(
        label = "Adult Content",
        description = "Block pornography and adult sites"
    ),
    GAMBLING(
        label = "Gambling",
        description = "Block betting, casinos, and lottery sites"
    ),
    DEEP_WEB(
        label = "Deep Web",
        description = "Block Tor gateways, darknet indexes, and hidden services"
    ),
    SOCIAL_MEDIA(
        label = "Social Media",
        description = "Block popular social and chat platforms"
    ),
    GAMING(
        label = "Online Gaming",
        description = "Block game stores, platforms, and gaming sites"
    ),
    DRUGS(
        label = "Drugs & Substances",
        description = "Block drug marketplaces and related content"
    ),
    MALWARE(
        label = "Malware & Phishing",
        description = "Block known malicious and phishing domains"
    )
}
