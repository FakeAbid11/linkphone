package dev.linkphone.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateRelease(
    val version: String,
    val url: String,
    val digest: String?,
    val size: Long
)

/** Shared GitHub Releases check used by the in-app updater and the background worker. */
object UpdateChecker {
    internal const val USER_AGENT = "LinkPhone-Android-Updater"
    private const val RELEASE_API =
        "https://api.github.com/repos/FakeAbid11/linkphone/releases/latest"
    private const val APK_NAME = "linkphone-android.apk"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchLatest(): UpdateRelease {
        val request = Request.Builder()
            .url(RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub update check failed (${response.code})")
            val root = JSONObject(response.body?.string() ?: error("Empty update response"))
            val version = root.optString("tag_name").removePrefix("v")
            if (version.isBlank()) error("Release has no version")
            val assets = root.optJSONArray("assets") ?: error("Release has no files")
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") != APK_NAME) continue
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/")) {
                    error("Release APK has an invalid download URL")
                }
                return UpdateRelease(
                    version = version,
                    url = url,
                    digest = asset.optString("digest").takeIf { it.isNotBlank() },
                    size = asset.optLong("size", -1L)
                )
            }
            error("Release does not contain $APK_NAME")
        }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val left = versionParts(candidate)
        val right = versionParts(current)
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun versionParts(value: String): List<Int> =
        value.removePrefix("v").split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
