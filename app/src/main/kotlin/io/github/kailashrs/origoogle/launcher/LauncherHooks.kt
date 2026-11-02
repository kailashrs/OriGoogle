package io.github.kailashrs.origoogle.launcher

import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.graphics.RectF
import android.os.IBinder
import android.view.MotionEvent
import io.github.kailashrs.origoogle.core.CornerAssistant
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks
import io.github.kailashrs.origoogle.system.ContextualSearchHooks

/**
 * com.bbk.launcher2 (vivo launcher, OriginOS 6) still contains the overseas Google integrations,
 * selected at runtime by FeatureUtils.isOversea() / environment defaults. These hooks enable the
 * Google variants only at their decision points and keep every other China behavior.
 */
object LauncherHooks {
    private const val QUICKSTEP = "com.android.quickstep"

    /** One-shot override consumed by the next FeatureUtils.isOversea() call on this thread. */
    private val overseaOnce = ThreadLocal<Boolean>()

    /** Scoped override for every isOversea() call within a hooked method. */
    private val overseaDepth = ThreadLocal<Int>()

    fun install(loader: ClassLoader) {
        val needOversea = Features.enabled(Features.CIRCLE_TO_SEARCH) || Features.enabled(Features.CORNER_ASSISTANT)
        if (needOversea) Hooks.install("launcher isOversea scope") { hookOverseaScope(loader) }
        if (Features.enabled(Features.CIRCLE_TO_SEARCH)) Hooks.install("launcher Circle to Search") { hookCircleToSearch(loader) }
        if (Features.enabled(Features.CORNER_ASSISTANT)) Hooks.install("launcher corner assistant") { hookCornerAssistant(loader) }
        if (Features.enabled(Features.DISCOVER_FEED)) Hooks.install("launcher Google Discover feed") { FeedHooks.install(loader) }
    }

    private fun hookOverseaScope(loader: ClassLoader) {
        val featureUtils = Hooks.findClass("$QUICKSTEP.utils.FeatureUtils", loader)
        Hooks.hook(Hooks.method(featureUtils, "isOversea")) { chain ->
            if (overseaOnce.get() == true) {
                overseaOnce.remove()
                return@hook true
            }
            if ((overseaDepth.get() ?: 0) > 0) true else chain.proceed()
        }
    }

    private fun withOversea(block: () -> Any?): Any? {
        overseaDepth.set((overseaDepth.get() ?: 0) + 1)
        try {
            return block()
        } finally {
            val next = (overseaDepth.get() ?: 1) - 1
            if (next > 0) overseaDepth.set(next) else overseaDepth.remove()
        }
    }

    private fun hookCircleToSearch(loader: ClassLoader) {
        // CircleSearchUtils reads the framework config string that OriginOS China leaves empty.
        val configId = Resources.getSystem().getIdentifier("config_defaultContextualSearchPackageName", "string", "android")
        if (configId != 0) {
            Hooks.hook(Hooks.method(Resources::class.java, "getString", Int::class.javaPrimitiveType!!)) { chain ->
                if (chain.args[0] != configId) return@hook chain.proceed()
                val value = chain.proceed() as? String
                if (value.isNullOrEmpty()) ContextualSearchHooks.GOOGLE_APP else value
            }
        }

        // The China branch ties the long-press to Jovi/Copilot state; follow our switch instead.
        val navigationMode = Hooks.findClass("$QUICKSTEP.SysUINavigationMode", loader)
        Hooks.hook(Hooks.method(navigationMode, "handleCircleSearchChanged")) { chain ->
            Hooks.set(chain.thisObject, "mCircleSearchEnabled", true)
            null
        }

        // shouldShowCircleSearchWhenLongPress() is immediately followed by
        // `isOversea() ? GoogleCircleSearchInputConsumer : CircleSearchInputConsumer`.
        val service = Hooks.findClass("$QUICKSTEP.TouchInteractionService", loader)
        val inputConsumer = Hooks.findClass("$QUICKSTEP.inputconsumers.InputConsumer", loader)
        Hooks.hook(Hooks.method(service, "shouldShowCircleSearchWhenLongPress", MotionEvent::class.java, inputConsumer)) { chain ->
            val result = chain.proceed()
            if (result == true) overseaOnce.set(true)
            result
        }
        Hooks.deoptimize(service, "onInputEvent")

        // Both overseas launch entry points are empty stubs in the China build.
        val controller = Hooks.findClass("$QUICKSTEP.circlesearch.GoogleCircleSearchController", loader)
        Hooks.hook(Hooks.method(controller, "invokeOmni", Context::class.java, Int::class.javaPrimitiveType!!)) { chain ->
            startContextualSearch(chain.args[1] as Int)
        }
        val proxy = Hooks.findClass("$QUICKSTEP.SystemUiProxy", loader)
        Hooks.hook(Hooks.method(proxy, "startCircleSearch", Int::class.javaPrimitiveType!!)) { chain ->
            startContextualSearch(chain.args[0] as Int)
            null
        }
    }

    /** Calls ContextualSearchManagerService directly; system_server trusts the launcher UID. */
    private fun startContextualSearch(entrypoint: Int): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "contextual_search") as? IBinder
            ?: error("contextual_search service is not running")
        val manager = Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        // Android 17 added a ContextualSearchConfig argument; null makes the service use its default config.
        val start = manager.javaClass.methods.first { it.name == "startContextualSearch" && it.parameterTypes[0] == Int::class.javaPrimitiveType }
        if (start.parameterCount == 1) start.invoke(manager, entrypoint) else start.invoke(manager, entrypoint, null)
        true
    }.getOrElse {
        Hooks.error("Circle to Search launch failed", it)
        false
    }

    private fun hookCornerAssistant(loader: ClassLoader) {
        // Corner regions are only measured on export builds.
        val regions = Hooks.findClass("$QUICKSTEP.NavigationTouchRegionHelper", loader)
        val initAssistantRegion = Hooks.method(regions, "initAssistantRegion", Int::class.javaPrimitiveType!!, Point::class.java)
        Hooks.hook(Hooks.method(regions, "initTouchBounds", Int::class.javaPrimitiveType!!, Point::class.java, String::class.java)) { chain ->
            val result = chain.proceed()
            val mode = Hooks.get(chain.thisObject, "mMode")
            if (mode != null && Hooks.get(mode, "hasGestures") == true) {
                initAssistantRegion.invoke(chain.thisObject, chain.args[0], chain.args[1])
            }
            result
        }

        // getCornerConsumer() picks AssistantTouchConsumer only when isOversea(). That is forced only for
        // a corner whose Interaction Cube choice is the assistant, so vivo's own corner actions keep working.
        val service = Hooks.findClass("$QUICKSTEP.TouchInteractionService", loader)
        Hooks.hookAll(service, "getCornerConsumer") { chain ->
            if (assistantCorner(chain.thisObject!!, chain.args[0] as MotionEvent)) withOversea { chain.proceed() } else chain.proceed()
        }
        Hooks.deoptimize(service, "getCornerConsumer", "canTriggerAssistantAction")

        // OriginOS 7 also turns its Google-assistant corner switch (mGoogleAssistSwitchEnabled) off whenever
        // the right corner opens vivo's multi-task view, which disables both corners. Inside a corner chosen
        // above, only the region check (done by assistantCorner) and the drag state count.
        val navigationMode = Hooks.findClass("$QUICKSTEP.SysUINavigationMode", loader)
        Hooks.hook(Hooks.method(navigationMode, "canTriggerAssistantAction", MotionEvent::class.java)) { chain ->
            val result = chain.proceed()
            if (result == true || (overseaDepth.get() ?: 0) == 0) result
            else Hooks.get(chain.thisObject!!, "mIsStartDrag") != true
        }
    }

    /** True when the touch lands in a bottom corner set to the assistant (see InteractionCubeHooks). */
    private fun assistantCorner(service: Any, event: MotionEvent): Boolean = runCatching {
        val navigation = Hooks.get(service, "mSysUINavigationMode")!!
        val regions = Hooks.get(navigation, "mNavigationTouchRegionHelper")!!
        val corner = when {
            (Hooks.call(regions, "getAssistantLeftRegion") as RectF).contains(event.x, event.y) -> CornerAssistant.Corner.LEFT
            (Hooks.call(regions, "getAssistantRightRegion") as RectF).contains(event.x, event.y) -> CornerAssistant.Corner.RIGHT
            else -> return@runCatching false
        }
        CornerAssistant.chosen(service as Context, corner)
    }.getOrElse {
        Hooks.error("corner assistant: corner check failed", it)
        false
    }
}
