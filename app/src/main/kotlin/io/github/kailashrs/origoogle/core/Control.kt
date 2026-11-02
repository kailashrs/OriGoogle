package io.github.kailashrs.origoogle.core

/** Broadcast contract between the settings app and the system_server part of the module. */
object Control {
    const val ACTION = "io.github.kailashrs.origoogle.CONTROL"
    const val PERMISSION = "io.github.kailashrs.origoogle.permission.CONTROL"
    const val EXTRA_COMMAND = "command"

    const val RESTART_UI = "restart_ui"
    const val SOFT_REBOOT = "soft_reboot"
    const val PREPARE_FEED = "prepare_feed"
}
