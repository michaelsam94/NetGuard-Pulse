package com.michael.netguardplus.system.hotspot

object HotspotCaptivePortalPage {

    fun html(deviceLabel: String? = null): String {
        val subtitle = deviceLabel?.let { "Device: ${escapeHtml(it)}" }
            ?: "This device has exceeded its data limit on this hotspot."
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Data Limit Reached</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 0; background: #0f172a; color: #e2e8f0; }
                .wrap { max-width: 420px; margin: 10vh auto; padding: 24px; text-align: center; }
                h1 { font-size: 1.5rem; margin-bottom: 8px; color: #f8fafc; }
                p { line-height: 1.5; color: #94a3b8; }
                .badge { display: inline-block; margin-top: 16px; padding: 8px 14px;
                  border-radius: 999px; background: #1e293b; color: #fbbf24; font-size: 0.9rem; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <h1>Data limit reached</h1>
                <p>$subtitle</p>
                <p>Internet access is paused until the hotspot owner restores access in NetGuard Pulse.</p>
                <div class="badge">NetGuard Pulse</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    fun limitReachedHtml(deviceName: String, dataUsed: String): String {
        val safeName = escapeHtml(deviceName)
        val safeData = escapeHtml(dataUsed)
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Data Limit Reached</title>
              <style>
                body { font-family: system-ui, sans-serif; margin: 0; background: #0f172a; color: #e2e8f0; }
                .wrap { max-width: 420px; margin: 10vh auto; padding: 24px; text-align: center; }
                h1 { font-size: 1.5rem; margin-bottom: 8px; color: #f8fafc; }
                p { line-height: 1.5; color: #94a3b8; }
                .info { margin: 16px 0; padding: 12px 16px; border-radius: 12px; background: #1e293b; }
                .info strong { color: #f8fafc; display: block; margin-bottom: 4px; }
                .badge { display: inline-block; margin-top: 16px; padding: 8px 14px;
                  border-radius: 999px; background: #334155; color: #fbbf24; font-size: 0.9rem; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <h1>Data Limit Reached</h1>
                <p>This device has exceeded its data limit on this hotspot.</p>
                <div class="info">
                  <strong>$safeName</strong>
                  <span>Data used: $safeData</span>
                </div>
                <p>Contact the hotspot owner to restore internet access in NetGuard Pulse.</p>
                <div class="badge">NetGuard Pulse</div>
              </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
