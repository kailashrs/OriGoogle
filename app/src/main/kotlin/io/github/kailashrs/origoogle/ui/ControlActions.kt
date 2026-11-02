package io.github.kailashrs.origoogle.ui

import android.content.Context
import android.content.Intent
import io.github.kailashrs.origoogle.core.Control

/** Asks the module's system_server part to act; no root grant is needed by this app. */
object ControlActions {
    fun prepareFeed(context: Context) = send(context, Control.PREPARE_FEED)

    fun restartUi(context: Context) = send(context, Control.RESTART_UI)

    fun softReboot(context: Context) = send(context, Control.SOFT_REBOOT)

    private fun send(context: Context, command: String) {
        context.sendBroadcast(
            Intent(Control.ACTION).setPackage("android").putExtra(Control.EXTRA_COMMAND, command),
        )
    }
}
