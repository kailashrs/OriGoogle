package io.github.kailashrs.origoogle.system

import android.content.pm.FeatureInfo
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks

/**
 * System feature view used by every app. OriginOS 6 China declares the CN-GMS features that put
 * Play services into its restricted China mode (no Location History, Quick Share, Find Hub, ...),
 * and lacks the contextual-search feature that launcher and Google app check for Circle to Search.
 *
 * PackageManagerService keeps SystemConfig's own ArrayMap as mAvailableFeatures, so editing the map
 * once at boot reaches hasSystemFeature, getSystemAvailableFeatures and the per-app feature cache.
 */
object FeatureHooks {
    const val CONTEXTUAL_SEARCH = "com.google.android.feature.CONTEXTUAL_SEARCH"
    private val cnGmsFeatures = setOf("cn.google.services", "com.google.android.feature.services_updater")

    fun install(loader: ClassLoader) {
        val gms = Features.enabled(Features.GMS)
        val circle = Features.enabled(Features.CIRCLE_TO_SEARCH)
        if (!gms && !circle) return

        val systemConfig = Hooks.findClass("com.android.server.SystemConfig", loader)
        Hooks.hook(Hooks.method(systemConfig, "getAvailableFeatures")) { chain ->
            val result = chain.proceed()
            @Suppress("UNCHECKED_CAST")
            (result as? MutableMap<String, FeatureInfo>)?.let { edit(it, gms, circle) }
            result
        }

        // Covers callers that ask before SystemConfig is read or that bypass the shared map.
        val pms = Hooks.findClass("com.android.server.pm.PackageManagerService", loader)
        Hooks.hook(Hooks.method(pms, "hasSystemFeature", String::class.java, Int::class.javaPrimitiveType!!)) { chain ->
            when (chain.args[0] as? String) {
                in cnGmsFeatures -> if (gms) false else chain.proceed()
                CONTEXTUAL_SEARCH -> if (circle) true else chain.proceed()
                else -> chain.proceed()
            }
        }
    }

    private fun edit(map: MutableMap<String, FeatureInfo>, gms: Boolean, circle: Boolean) {
        if (gms) cnGmsFeatures.forEach { map.remove(it) }
        if (circle && CONTEXTUAL_SEARCH !in map) {
            map[CONTEXTUAL_SEARCH] = FeatureInfo().apply { name = CONTEXTUAL_SEARCH }
        }
    }
}
