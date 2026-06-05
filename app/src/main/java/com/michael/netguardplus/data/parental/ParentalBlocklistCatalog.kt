package com.michael.netguardplus.data.parental

import com.michael.netguardplus.domain.model.ParentalCategory

/**
 * Curated seed domains per parental category. Toggle categories in the Block tab
 * to merge these into the local DNS blocklist. For broader coverage, also enable
 * a family-safe upstream DNS provider.
 */
object ParentalBlocklistCatalog {

    private val catalog: Map<ParentalCategory, Set<String>> = mapOf(
        ParentalCategory.ADULT to setOf(
            "pornhub.com",
            "xvideos.com",
            "xnxx.com",
            "xhamster.com",
            "redtube.com",
            "youporn.com",
            "chaturbate.com",
            "livejasmin.com",
            "onlyfans.com",
            "brazzers.com",
            "bangbros.com",
            "spankbang.com",
            "eporner.com",
            "hqporner.com",
            "porn.com",
            "adultfriendfinder.com",
            "literotica.com",
            "rule34.xxx",
            "nhentai.net",
            "fakku.net"
        ),
        ParentalCategory.GAMBLING to setOf(
            "bet365.com",
            "pokerstars.com",
            "888casino.com",
            "williamhill.com",
            "betfair.com",
            "draftkings.com",
            "fanduel.com",
            "bwin.com",
            "ladbrokes.com",
            "paddypower.com",
            "betway.com",
            "unibet.com",
            "stake.com",
            "1xbet.com",
            "betonline.ag",
            "bovada.lv",
            "casino.com",
            "partypoker.com",
            "sportsbetting.ag",
            "betsson.com"
        ),
        ParentalCategory.DEEP_WEB to setOf(
            "torproject.org",
            "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
            "thehiddenwiki.org",
            "dark.fail",
            "deepweblinks.net",
            "tor2web.org",
            "onion.link",
            "onion.ws",
            "onion.pet",
            "ahmia.fi",
            "torchsearch.com",
            "darknetlive.com",
            "dreadforum.net",
            "exploit.in",
            "breached.to",
            "hackforums.net",
            "nulled.to",
            "cracked.to",
            "raidforums.com",
            "breachforums.st"
        ),
        ParentalCategory.SOCIAL_MEDIA to setOf(
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com",
            "tiktok.com",
            "snapchat.com",
            "reddit.com",
            "discord.com",
            "discord.gg",
            "telegram.org",
            "t.me",
            "whatsapp.com",
            "messenger.com",
            "linkedin.com",
            "pinterest.com",
            "tumblr.com",
            "threads.net",
            "bsky.app",
            "mastodon.social",
            "wechat.com"
        ),
        ParentalCategory.GAMING to setOf(
            "steampowered.com",
            "steamcommunity.com",
            "store.steampowered.com",
            "epicgames.com",
            "roblox.com",
            "minecraft.net",
            "battle.net",
            "ea.com",
            "origin.com",
            "ubisoft.com",
            "playstation.com",
            "xbox.com",
            "nintendo.com",
            "twitch.tv",
            "discord.com",
            "gog.com",
            "itch.io",
            "humblebundle.com",
            "gamejolt.com",
            "chess.com"
        ),
        ParentalCategory.DRUGS to setOf(
            "silkroad.com",
            "alphabaymarket.com",
            "dreammarket.org",
            "empiremarket.org",
            "darknetmarkets.org",
            "erowid.org",
            "bluelight.org",
            "drugs-forum.com",
            "shroomery.org",
            "grasscity.com",
            "leafly.com",
            "weedmaps.com",
            "hightimes.com",
            "norml.org",
            "420magazine.com",
            "growweedeasy.com",
            "seedsman.com",
            "sensiseeds.com",
            "herbiesheadshop.com",
            "royalqueenseeds.com"
        ),
        ParentalCategory.MALWARE to setOf(
            "malwaredomainlist.com",
            "urlhaus.abuse.ch",
            "openphish.com",
            "phishing.army",
            "badsite.example",
            "secure-fraud-check.com",
            "account-verify-now.com",
            "login-security-alert.com",
            "update-windows-now.com",
            "free-antivirus-download.net",
            "prize-winner-claim.com",
            "bank-login-verify.com",
            "crypto-airdrop-claim.io",
            "wallet-connect-verify.com",
            "support-apple-id.com",
            "microsoft-account-locked.com",
            "paypal-security-check.com",
            "amazon-order-cancel.com",
            "netflix-billing-update.com",
            "google-account-recovery.net"
        )
    )

    fun domainsFor(categories: Set<ParentalCategory>): Set<String> {
        if (categories.isEmpty()) return emptySet()
        return categories.flatMap { catalog[it].orEmpty() }.toSet()
    }

    fun domainCount(category: ParentalCategory): Int = catalog[category]?.size ?: 0

    /** Hostnames used by DNS-over-HTTPS / Private DNS — block so queries use the VPN filter. */
    val dohBypassDomains: Set<String> = setOf(
        "dns.google",
        "dns.google.com",
        "dns64.dns.google",
        "chrome.cloudflare-dns.com",
        "cloudflare-dns.com",
        "one.one.one.one",
        "mozilla.cloudflare-dns.com",
        "security.cloudflare-dns.com",
        "family.cloudflare-dns.com",
        "dns.quad9.net",
        "dns.adguard.com",
        "dns.adguard-dns.com",
        "doh.opendns.com",
        "dns.nextdns.io",
        "dns.alidns.com",
        "doh.cleanbrowsing.org"
    )
}
