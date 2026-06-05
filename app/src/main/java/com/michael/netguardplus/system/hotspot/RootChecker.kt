package com.michael.netguardplus.system.hotspot

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Lightweight root-detection helper.
 *
 * Checks three independent signals:
 *  1. Well-known `su` binary paths (fast, no execution needed)
 *  2. Android build-tag "test-keys" (common on rooted factory images)
 *  3. Attempt to execute `su -c id` (definitive, but capped at 600 ms)
 *
 * Any single positive signal returns `true`.
 */
object RootChecker {

    private const val TAG = "RootChecker"

    /** Returns `true` if the device appears to be rooted. */
    fun isRooted(): Boolean =
        checkSuBinary() || checkBuildTags() || checkSuCommand()

    // ── checks ──────────────────────────────────────────────────────────────

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/system/app/Superuser.apk",
            "/system/app/SuperSU/SuperSU.apk"
        )
        return paths.any { File(it).exists() }.also { found ->
            if (found) Log.d(TAG, "su binary found on filesystem")
        }
    }

    private fun checkBuildTags(): Boolean {
        val tags = android.os.Build.TAGS ?: return false
        return tags.contains("test-keys").also { found ->
            if (found) Log.d(TAG, "Build tags indicate rooted image: $tags")
        }
    }

    private fun checkSuCommand(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exited = proc.waitFor(600, TimeUnit.MILLISECONDS)
            if (!exited) {
                proc.destroyForcibly()
                return false
            }
            (proc.exitValue() == 0).also { succeeded ->
                if (succeeded) Log.d(TAG, "su command executed successfully")
            }
        } catch (e: Exception) {
            Log.d(TAG, "su command unavailable: ${e.message}")
            false
        }
    }
}
