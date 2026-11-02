package io.github.kailashrs.origoogle.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks

/**
 * vivo's share panel (com.android.intentresolver) reads Secure "nearby_sharing_component" first,
 * then config_defaultNearbySharingComponent (empty on China builds), and pins that component as the
 * first app target. Quick Share's send activity is resolved from Play services at runtime, because
 * GMS can enable or rename it with an update; a value set by anyone else is never overwritten.
 */
object QuickShareHooks {
    private const val GMS = "com.google.android.gms"
    private const val SETTING = "nearby_sharing_component"

    fun install(loader: ClassLoader) {
        val ams = Hooks.findClass("com.android.server.am.ActivityManagerService", loader)
        Hooks.hookAll(ams, "systemReady") { chain ->
            val result = chain.proceed()
            runCatching { register(Hooks.get(chain.thisObject, "mContext") as Context) }
                .onFailure { Hooks.error("Quick Share receiver registration failed", it) }
            result
        }
    }

    private fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.data != null && intent.data?.schemeSpecificPart != GMS) return
                runCatching { update(context) }.onFailure { Hooks.error("Quick Share update failed", it) }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BOOT_COMPLETED), Context.RECEIVER_EXPORTED)
        val packages = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, packages, Context.RECEIVER_EXPORTED)
    }

    private fun update(context: Context) {
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, SETTING)
        // Only a value this module could have written (a GMS component) or none is ours to manage.
        if (!current.isNullOrEmpty() && !current.startsWith("$GMS/")) return

        val target = if (Features.enabled(Features.QUICK_SHARE)) resolve(context.packageManager) else null
        if (target == current || (target == null && current.isNullOrEmpty())) return
        Settings.Secure.putString(resolver, SETTING, target)
        Hooks.info("Quick Share: $SETTING = $target")
    }

    /** Play services' Quick Share activity for ACTION_SEND, or null when GMS does not offer it. */
    private fun resolve(pm: PackageManager): String? {
        for (type in listOf("text/plain", "image/jpeg")) {
            val intent = Intent(Intent.ACTION_SEND).setType(type).setPackage(GMS)
            val activity = pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo }
                .firstOrNull { it.exported && it.name.contains(".nearby.sharing.") }
            if (activity != null) return ComponentName(activity.packageName, activity.name).flattenToString()
        }
        Hooks.info("Quick Share: no Play services share activity found")
        return null
    }
}
