package io.github.kailashrs.origoogle.system

import android.content.Context
import android.os.Binder
import android.os.Process
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks

/**
 * Starts AOSP ContextualSearchManagerService (Circle to Search) on OriginOS 6 China, which skips it
 * because config_defaultContextualSearchPackageName is empty, and lets the vivo launcher call it.
 */
object ContextualSearchHooks {
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    private val trustedPackages = setOf("com.android.systemui", "com.bbk.launcher2")

    fun install(loader: ClassLoader) {
        if (!Features.enabled(Features.CIRCLE_TO_SEARCH)) return
        val configId = Hooks.findClass("com.android.internal.R\$string", null)
            .getField("config_defaultContextualSearchPackageName").getInt(null)

        val systemServer = Hooks.findClass("com.android.server.SystemServer", loader)
        Hooks.hook(Hooks.method(systemServer, "deviceHasConfigString", Context::class.java, Int::class.javaPrimitiveType!!)) { chain ->
            if (chain.args[1] == configId) true else chain.proceed()
        }

        val service = Hooks.findClass("com.android.server.contextualsearch.ContextualSearchManagerService", loader)
        Hooks.hook(Hooks.method(service, "getContextualSearchPackageName")) { chain ->
            val configured = chain.proceed() as? String
            if (configured.isNullOrEmpty()) GOOGLE_APP else configured
        }
        Hooks.hook(Hooks.method(service, "enforcePermission", String::class.java)) { chain ->
            val context = Hooks.call(chain.thisObject, "getContext") as Context
            if (isTrustedCaller(context)) null else chain.proceed()
        }
    }

    private fun isTrustedCaller(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.SYSTEM_UID) return true
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return packages.any { it in trustedPackages }
    }
}
