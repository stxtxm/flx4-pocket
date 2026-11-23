package io.github.timotheegrollier.flx4pocket

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * The in-app updater, driven entirely from one top-bar button so it never
 * gets in the way of a mix: background checks are silent, the download shows
 * its progress on the button, and nothing is installed until the user taps
 * INSTALL.
 */
class UpdateUi(
    private val activity: Activity,
    private val button: TextView,
    private val work: ExecutorService,
    private val isPlaying: () -> Boolean
) {
    private val ui = Handler(Looper.getMainLooper())
    private var info: UpdateChecker.UpdateInfo? = null
    private var apk: File? = null
    private var checking = false
    private var progress = -1 // download percentage, -1 when idle
    private var waitingForPermission = false

    private val periodicCheck = object : Runnable {
        override fun run() {
            check(manual = false)
            ui.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        button.setOnClickListener { onClick() }
        render()
        ui.postDelayed(periodicCheck, 3000)
    }

    fun stop() = ui.removeCallbacksAndMessages(null)

    /** Call from onResume: continues an install that was waiting for the system setting. */
    fun onResume() {
        if (waitingForPermission && activity.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            install()
        }
    }

    private fun onClick() {
        val update = info
        when {
            apk != null -> if (isPlaying()) confirmInstallWhilePlaying() else install()
            progress >= 0 -> toast(activity.getString(R.string.update_downloading))
            update != null -> showNotes(update)
            else -> check(manual = true)
        }
    }

    private fun check(manual: Boolean) {
        if (checking || info != null) return
        checking = true
        render()
        work.execute {
            val found = try {
                UpdateChecker.check(
                    BuildConfig.UPDATE_OWNER, BuildConfig.UPDATE_REPO, BuildConfig.VERSION_CODE.toLong()
                )
            } catch (_: Exception) {
                null
            }
            ui.post {
                checking = false
                info = found
                render()
                when {
                    !manual -> Unit // background checks only light up the button
                    found != null -> showNotes(found)
                    else -> toast(
                        UpdateChecker.lastError
                            ?: activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
                    )
                }
            }
        }
    }

    private fun showNotes(update: UpdateChecker.UpdateInfo) {
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title, update.versionName))
            .setMessage(update.notes.take(1400).ifBlank { "Bug fixes and improvements." })
            .setPositiveButton(R.string.update_download) { _, _ -> download(update) }
            .setNeutralButton(R.string.update_github) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.htmlUrl))) }
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(update: UpdateChecker.UpdateInfo) {
        if (progress >= 0) return
        progress = 0
        render()
        work.execute {
            val result = runCatching {
                UpdateChecker.download(
                    info = update,
                    cacheDir = activity.cacheDir,
                    onProgress = { percent ->
                        if (percent != progress) ui.post {
                            progress = percent
                            render()
                        }
                    },
                    cancelled = { false }
                )
            }
            ui.post {
                progress = -1
                apk = result.getOrNull()
                render()
                result.onSuccess { toast(activity.getString(R.string.update_ready)) }
                result.onFailure { alert(R.string.update_download_failed, it.message) }
            }
        }
    }

    private fun confirmInstallWhilePlaying() {
        AlertDialog.Builder(activity)
            .setMessage(R.string.update_confirm_playing)
            .setPositiveButton(R.string.update_install_now) { _, _ -> install() }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun install() {
        val file = apk ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.allow_installs_title)
                .setMessage(R.string.allow_installs_message)
                .setPositiveButton(R.string.settings_open) { _, _ ->
                    waitingForPermission = true
                    runCatching {
                        activity.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        try {
            UpdateInstaller.install(activity, file)
            toast(activity.getString(R.string.update_confirm_system))
        } catch (e: Exception) {
            alert(R.string.update_install_failed, e.message)
        }
    }

    private fun render() {
        val update = info
        val panel = activity.getColor(R.color.panel_alt)
        val text = activity.getColor(R.color.text)
        val dark = activity.getColor(R.color.on_accent)
        when {
            apk != null && update != null -> {
                button.show(activity.getString(R.string.update_install, update.versionName))
                button.tint(activity.getColor(R.color.ok), dark)
            }
            progress >= 0 -> {
                button.show("↓ $progress%")
                button.tint(panel, activity.getColor(R.color.accent))
            }
            update != null -> {
                button.show(activity.getString(R.string.update_available, update.versionName))
                button.tint(activity.getColor(R.color.accent), dark)
            }
            checking -> {
                button.show(activity.getString(R.string.update_checking))
                button.tint(panel, activity.getColor(R.color.text_dim))
            }
            else -> {
                button.show("v${BuildConfig.VERSION_NAME}")
                button.tint(panel, text)
            }
        }
    }

    private fun alert(title: Int, message: String?) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message ?: "")
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
