package io.github.timotheegrollier.flx4pocket

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Reads the update manifest published by the GitHub Actions release workflow:
 * https://github.com/stxtxm/flx4-pocket/releases/latest
 *
 * The manifest is a small asset named update.json containing versionCode,
 * versionName, apk, sha256 and notes. Downloading the whole APK is only done
 * after the user taps "Download & install".
 */
object UpdateChecker {

    const val GITHUB_UNREACHABLE = "GitHub was unreachable (check your connection and try again)"

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val apkName: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val htmlUrl: String
    )

    /**
     * The reason the last [check] returned null: either no update is available
     * or GitHub (or its manifest) could not be reached. Used to tell the user
     * apart from a plain "no update found".
     */
    @Volatile
    var lastError: String? = null
        private set

    fun check(owner: String, repo: String, currentVersionCode: Long): UpdateInfo? {
        lastError = null
        val release = getJson("https://api.github.com/repos/$owner/$repo/releases/latest")
        if (release == null) {
            lastError = GITHUB_UNREACHABLE
            return null
        }
        val assets = release.optJSONArray("assets")
        if (assets == null) {
            lastError = GITHUB_UNREACHABLE
            return null
        }
        var manifestUrl: String? = null
        val apkAssets = HashMap<String, JSONObject>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name == "update.json") manifestUrl = url
            if (name.endsWith(".apk", ignoreCase = true)) apkAssets[name] = asset
        }
        val manifest = manifestUrl?.let { getJson(it) }
        if (manifest == null) {
            lastError = GITHUB_UNREACHABLE
            return null
        }
        val versionCode = manifest.optLong("versionCode", 0L)
        if (versionCode <= currentVersionCode) return null

        val apkName = manifest.optString("apk")
        val apkAsset = apkAssets[apkName] ?: apkAssets.values.firstOrNull() ?: return null
        val apkUrl = apkAsset.optString("browser_download_url")
        if (apkUrl.isBlank()) return null
        val digest = apkAsset.optString("digest").removePrefix("sha256:")
        val sha = manifest.optString("sha256").ifBlank { digest }
        return UpdateInfo(
            versionCode = versionCode,
            versionName = manifest.optString("versionName", release.optString("tag_name")),
            apkUrl = apkUrl,
            apkName = apkName.ifBlank { apkAsset.optString("name") },
            sha256 = sha,
            size = apkAsset.optLong("size", 0L),
            notes = manifest.optString("notes", release.optString("body")),
            htmlUrl = release.optString("html_url")
        )
    }

    fun download(
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean
    ): File {
        val destination = File(cacheDir, "flx4-update-${info.versionCode}.apk")
        if (destination.exists()) destination.delete()

        val connection = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FLX4-Pocket-Android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP $code")
            }
            val expectedSize = if (info.size > 0) info.size else connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) throw IOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                        if (expectedSize > 0) {
                            onProgress(((total * 100) / expectedSize).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            onProgress(100)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotBlank() && !actual.equals(info.sha256, ignoreCase = true)) {
                destination.delete()
                throw IOException("Downloaded APK failed its SHA-256 check")
            }
            return destination
        } catch (e: Exception) {
            destination.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "FLX4-Pocket-Android")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
