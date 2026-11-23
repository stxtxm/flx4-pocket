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

    /**
     * Verifies the downloaded APK before installing it.

     * The checks reflect the things that used to break the in-app updater on a
     * Pixel 9a / Android 15 device:
     *  - the file must actually be an APK and non-empty,
     *  - it must target the same package,
     *  - it must be newer than the installed build,
     *  - it must be signed with the same key as the installed release.

     * The hash against `update.json` (SHA-256 + length) is verified by
     * `UpdateChecker` before this method is called.
     */
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

    /**
     * Installs a verified APK through [PackageInstaller].

     * This is the call that was failing with "No update found / GitHub unreachable"
     * even when the download and hash check had succeeded: the old code used an
     * immutable [PendingIntent] on Android 12+, which prevented the system from
     * delivering the install result through the broadcast receiver. The receiver
     * then retried / reported a download failure, so the UI ended up showing the
     * generic error.

     * The fix is to use `FLAG_MUTABLE` for SDK >= 31, which is required for any
     * PendingIntent that the system is expected to fill (here: EXTRA_STATUS).
     * Android still shows its own confirmation dialog and the user must accept it;
     * that step cannot be bypassed from a normal app.
     *
     * Installation only replaces the current app when the new APK is signed with
     * the same key; this is why the release workflow always signs with the same
     * keystore as the previous version.
     */
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
                    java.io.FileInputStream(apk).use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = android.content.Intent(context, InstallResultReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .putExtra(EXTRA_APK_PATH, apk.absolutePath)
                // PackageInstaller delivers EXTRA_STATUS through this PendingIntent,
                // so it must be mutable on Android 12+. The system fills the extras;
                // an immutable one would hand the receiver an empty result.
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    pendingFlags
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
