package io.github.kailashrs.origoogle

import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks
import io.github.kailashrs.origoogle.interaction.InteractionCubeHooks
import io.github.kailashrs.origoogle.launcher.LauncherHooks
import io.github.kailashrs.origoogle.system.ContextualSearchHooks
import io.github.kailashrs.origoogle.system.ControlReceiver
import io.github.kailashrs.origoogle.system.FeatureHooks
import io.github.kailashrs.origoogle.system.FeedWindowHooks
import io.github.kailashrs.origoogle.system.PowerKeyHooks
import io.github.kailashrs.origoogle.system.QuickShareHooks
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class OriGoogleModule : XposedModule() {
    private val readyPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Hooks.attach(this)
        Features.attach(runCatching { getRemotePreferences(Features.GROUP) }.getOrNull())
        Hooks.info("OriGoogle ${BuildConfig.VERSION_NAME} loaded in ${param.processName} ($frameworkName API $apiVersion)")
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (!isOriginOs()) return
        Hooks.logFile = java.io.File("/data/system/origoogle.log")
        val loader = param.classLoader
        Hooks.install("system features (GMS, contextual search)") { FeatureHooks.install(loader) }
        Hooks.install("contextual search service") { ContextualSearchHooks.install(loader) }
        Hooks.install("Quick Share in share sheet") { QuickShareHooks.install(loader) }
        Hooks.install("power key (assistant)") { PowerKeyHooks.install(loader) }
        Hooks.install("control receiver") { ControlReceiver.install(loader) }
        Hooks.install("Discover feed window") { FeedWindowHooks.install(loader) }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        // OriginOS processes can load another package before their own (isFirstPackage is false
        // for com.bbk.launcher2), so de-duplicate by name instead.
        if (!readyPackages.add(param.packageName) || !isOriginOs()) return
        if (Hooks.logFile == null) Hooks.logFile = java.io.File(param.applicationInfo.dataDir, "cache/origoogle.log")
        Hooks.info("package ready: ${param.packageName} (first=${param.isFirstPackage})")
        when (param.packageName) {
            "com.bbk.launcher2" -> LauncherHooks.install(param.classLoader)
            "com.vivo.upslide" -> if (Features.enabled(Features.CORNER_ASSISTANT)) {
                Hooks.install("Interaction Cube corner assistant") { InteractionCubeHooks.install(param.classLoader) }
            }
        }
    }

    /** Hooks are bound to OriginOS 6/7 class layouts (checked on 7.0, launcher 17.0.0.381); do nothing elsewhere. */
    private fun isOriginOs(): Boolean = runCatching {
        val get = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        (get.invoke(null, "ro.vivo.os.build.display.id") as String).startsWith("OriginOS")
    }.getOrDefault(false)
}
