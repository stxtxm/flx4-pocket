package io.github.timotheegrollier.flx4pocket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * Installs a downloaded APK through PackageInstaller.
 *
 * Android always shows its own confirmation screen for a non-privileged app.
 * The app therefore prepares the session and forwards the system prompt; it
 * cannot and should not suppress it. Keeping the same signing key across
 * releases is what makes the update install over the existing package.
 */
object UpdateInstaller {

    const val ACTION_INSTALL_RESULT = "io.github.timotheegrollier.flx4pocket.INSTALL_RESULT"
    const val EXTRA_APK_PATH = "apk_path"

    fun verify(context: Context, apk: File) {
        if (!apk.exists() || apk.length() <= 0L) {
            throw IllegalStateException("The downloaded file is empty")
        }
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalStateException("The downloaded file is not an APK")
        if (archive.packageName != context.packageName) {
            throw IllegalStateException("This APK is for another app")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        val current = packageManager.getPackageInfo(
            context.packageName,
            if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        )
        val currentCode = if (Build.VERSION.SDK_INT >= 28) {
            current.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            current.versionCode.toLong()
        }
        if (archiveCode <= currentCode) {
            throw IllegalStateException("This update is not newer than the installed app")
        }

        if (Build.VERSION.SDK_INT >= 28) {
            val newSigners = archive.signingInfo?.apkContentsSigners
            val currentSigners = current.signingInfo?.apkContentsSigners
            if (newSigners != null && currentSigners != null) {
                val newSet = newSigners.map { it.toCharsString() }.toSet()
                val oldSet = currentSigners.map { it.toCharsString() }.toSet()
                if (newSet != oldSet) {
                    throw IllegalStateException(
                        "The update is signed with a different key. " +
                            "Install the release APK from the same channel instead."
                    )
                }
            }
        }
    }

    fun install(context: Context, apk: File) {
        verify(context, apk)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    FileInputStream(apk).use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .putExtra(EXTRA_APK_PATH, apk.absolutePath)
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        }
    }
}
