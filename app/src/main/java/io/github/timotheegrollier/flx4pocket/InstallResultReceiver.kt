package io.github.timotheegrollier.flx4pocket

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                }
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmation)
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            "Could not open the Android installer: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val path = intent.getStringExtra(UpdateInstaller.EXTRA_APK_PATH)
                if (!path.isNullOrBlank()) File(path).delete()
                Toast.makeText(context, "FLX4 Pocket updated", Toast.LENGTH_LONG).show()
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: "Installation failed"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
