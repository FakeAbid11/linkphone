package dev.linkphone.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class AppUpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, UP_TO_DATE, ERROR }

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    val availableVersion: String = "",
    val progress: Int = 0,
    val message: String = ""
)

/**
 * Checks this project's public GitHub release and downloads a signature-verified
 * APK. Android always owns the final install confirmation; silent installs are
 * unavailable to ordinary sideloaded apps.
 */
class AppUpdateManager(context: Context) {
    private data class Release(
        val version: String,
        val url: String,
        val digest: String?,
        val size: Long
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val _state = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    @Volatile private var release: Release? = null
    @Volatile private var downloaded: File? = null
    @Volatile private var lastCheckedAt = 0L

    fun check(force: Boolean = true) {
        if (_state.value.phase == AppUpdatePhase.CHECKING ||
            _state.value.phase == AppUpdatePhase.DOWNLOADING
        ) return
        if (!force && System.currentTimeMillis() - lastCheckedAt < AUTO_CHECK_INTERVAL_MS) return
        _state.value = _state.value.copy(
            phase = AppUpdatePhase.CHECKING,
            message = "",
            progress = 0
        )
        scope.launch {
            runCatching { fetchLatest() }
                .onSuccess { latest ->
                    lastCheckedAt = System.currentTimeMillis()
                    release = latest
                    if (isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                        _state.value = AppUpdateState(
                            phase = AppUpdatePhase.AVAILABLE,
                            availableVersion = latest.version,
                            message = "Update available"
                        )
                    } else {
                        _state.value = AppUpdateState(
                            phase = AppUpdatePhase.UP_TO_DATE,
                            message = "You have the latest version"
                        )
                    }
                }
                .onFailure { fail(it) }
        }
    }

    fun download() {
        val target = release ?: run {
            check(true)
            return
        }
        if (_state.value.phase == AppUpdatePhase.DOWNLOADING) return
        _state.value = _state.value.copy(
            phase = AppUpdatePhase.DOWNLOADING,
            progress = 0,
            message = "Downloading from GitHub Releases"
        )
        scope.launch {
            runCatching { downloadAndVerify(target) }
                .onSuccess { file ->
                    downloaded = file
                    _state.value = _state.value.copy(
                        phase = AppUpdatePhase.READY,
                        progress = 100,
                        message = "Ready to install"
                    )
                }
                .onFailure { fail(it) }
        }
    }

    fun install() {
        val apk = downloaded
        if (apk == null || !apk.isFile) {
            _state.value = _state.value.copy(
                phase = AppUpdatePhase.ERROR,
                message = "Downloaded update is missing; download it again"
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            _state.value = _state.value.copy(
                message = "Allow installs from LinkPhone, then tap Install again"
            )
            runCatching {
                appContext.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { fail(it) }
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                apk
            )
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { fail(it) }
    }

    private fun fetchLatest(): Release {
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
                return Release(
                    version = version,
                    url = url,
                    digest = asset.optString("digest").takeIf { it.isNotBlank() },
                    size = asset.optLong("size", -1L)
                )
            }
            error("Release does not contain $APK_NAME")
        }
    }

    private fun downloadAndVerify(release: Release): File {
        val directory = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "linkphone-${release.version}.apk.part")
        val final = File(directory, "linkphone-${release.version}.apk")
        directory.listFiles()?.forEach { file ->
            if (file != partial && file != final) file.delete()
        }
        partial.delete()
        final.delete()

        val request = Request.Builder()
            .url(release.url)
            .header("User-Agent", USER_AGENT)
            .build()
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("APK download failed (${response.code})")
            val body = response.body ?: error("APK download was empty")
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            if (total > MAX_APK_BYTES) error("Release APK is unexpectedly large")
            body.byteStream().use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        if (received > MAX_APK_BYTES) error("Release APK is unexpectedly large")
                        if (total > 0) {
                            val progress = ((received * 100) / total).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _state.value = _state.value.copy(progress = progress)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
        }

        val actualHash = digest.digest().toHex()
        val expectedHash = release.digest
            ?.removePrefix("sha256:")
            ?.lowercase()
        if (expectedHash != null && expectedHash != actualHash) {
            partial.delete()
            error("Downloaded APK failed its SHA-256 check")
        }
        if (!verifySignature(partial)) {
            partial.delete()
            error("Downloaded APK is not signed by LinkPhone")
        }
        if (!partial.renameTo(final)) {
            partial.copyTo(final, overwrite = true)
            partial.delete()
        }
        return final
    }

    @Suppress("DEPRECATION")
    private fun verifySignature(apk: File): Boolean {
        val manager = appContext.packageManager
        val archive = manager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES
        ) ?: return false
        if (archive.packageName != appContext.packageName) return false
        if (archive.longVersionCode <= BuildConfig.VERSION_CODE) return false

        val installed = manager.getPackageInfo(
            appContext.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        val archiveHashes = archive.signingInfo?.apkContentsSigners.orEmpty().map { signerHash(it.toByteArray()) }.toSet()
        val installedHashes = installed.signingInfo?.apkContentsSigners.orEmpty().map { signerHash(it.toByteArray()) }.toSet()
        return archiveHashes.isNotEmpty() && archiveHashes == installedHashes
    }

    private fun fail(error: Throwable) {
        _state.value = _state.value.copy(
            phase = AppUpdatePhase.ERROR,
            progress = 0,
            message = (error.message ?: "Update failed").take(240)
        )
    }

    private fun signerHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun isNewer(candidate: String, current: String): Boolean {
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

    companion object {
        private const val RELEASE_API =
            "https://api.github.com/repos/FakeAbid11/linkphone/releases/latest"
        private const val APK_NAME = "linkphone-android.apk"
        private const val USER_AGENT = "LinkPhone-Android-Updater"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_APK_BYTES = 200L * 1024 * 1024
    }
}
