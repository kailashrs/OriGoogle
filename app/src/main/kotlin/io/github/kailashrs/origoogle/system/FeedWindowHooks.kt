package io.github.kailashrs.origoogle.system

import android.view.WindowManager
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks

/**
 * Discover's overlay window ("GoogleDiscoverWindow") is added by the Google app onto the vivo
 * launcher's token with PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP, which DisplayPolicy reserves
 * for MANAGE_ACTIVITY_TASKS holders (privileged Google app on Pixels). Drop only that flag.
 */
object FeedWindowHooks {
    private const val TITLE = "GoogleDiscoverWindow"
    private const val PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP = Int.MIN_VALUE

    fun install(loader: ClassLoader) {
        if (!Features.enabled(Features.DISCOVER_FEED)) return
        val privateFlags = Hooks.field(WindowManager.LayoutParams::class.java, "privateFlags")
        // DisplayPolicy.validateAddingWindowLw, where the permission is enforced, gets inlined into
        // addWindow (OriginOS 7 17.2.11.5 even with addWindow deoptimized), so the flag is dropped
        // at WindowManagerService.addWindow, before that check runs.
        val wms = Hooks.findClass("com.android.server.wm.WindowManagerService", loader)
        val addWindow = wms.declaredMethods.filter { method ->
            method.name == "addWindow" && WindowManager.LayoutParams::class.java in method.parameterTypes
        }
        if (addWindow.isEmpty()) throw NoSuchMethodException("${wms.name}#addWindow(LayoutParams)")
        addWindow.forEach { method ->
            val index = method.parameterTypes.indexOf(WindowManager.LayoutParams::class.java)
            Hooks.hook(method) { chain ->
                val attrs = chain.args[index] as? WindowManager.LayoutParams
                // Google creates the window from the launcher's context, so packageName is not reliable.
                // Only a privilege is removed here, so matching the title cannot grant anything.
                if (attrs != null && attrs.title?.toString() == TITLE) {
                    val flags = privateFlags.getInt(attrs)
                    Hooks.info("feed window: pkg=${attrs.packageName} privateFlags=0x${Integer.toHexString(flags)}")
                    if (flags and PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP != 0) {
                        privateFlags.setInt(attrs, flags and PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP.inv())
                        Hooks.info("feed window: dropped global drag-and-drop interception")
                    }
                }
                chain.proceed()
            }
        }
    }
}
