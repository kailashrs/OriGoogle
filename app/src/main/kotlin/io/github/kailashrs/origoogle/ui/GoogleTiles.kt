package io.github.kailashrs.origoogle.ui

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * vivo's control center only builds its own tile classes, so the AOSP Wallet / QR scanner tiles
 * cannot be shown; it does host third-party TileServices. These launch the Google equivalents.
 */
abstract class LaunchTile : TileService() {
    abstract fun intent(): Intent?

    override fun onStartListening() {
        qsTile?.apply {
            state = if (intent()?.resolveActivity(packageManager) != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        val target = intent()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val pending = PendingIntent.getActivity(this, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (isLocked) unlockAndRun { startActivityAndCollapse(pending) } else startActivityAndCollapse(pending)
    }
}

class WalletTile : LaunchTile() {
    override fun intent(): Intent? = packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel")
}

class LensTile : LaunchTile() {
    override fun intent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("google://lens"))
        .setPackage("com.google.android.googlequicksearchbox")
}

class GeminiTile : LaunchTile() {
    override fun intent(): Intent? = packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")
}
