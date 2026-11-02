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
        val policy = Hooks.findClass("com.android.server.wm.DisplayPolicy", loader)
        // validateAddingWindowLw is small enough to be inlined into addWindow.
        Hooks.deoptimize(Hooks.findClass("com.android.server.wm.WindowManagerService", loader), "addWindow")
        Hooks.hook(
            Hooks.method(policy, "validateAddingWindowLw", WindowManager.LayoutParams::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        ) { chain ->
            val attrs = chain.args[0] as WindowManager.LayoutParams
            // Google creates the window from the launcher's context, so packageName is not reliable.
            // Only a privilege is removed here, so matching the title cannot grant anything.
            if (attrs.title?.toString() == TITLE) {
                Hooks.info("feed window: pkg=${attrs.packageName} privateFlags=0x${Integer.toHexString(privateFlags.getInt(attrs))}")
                val flags = privateFlags.getInt(attrs)
                if (flags and PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP != 0) {
                    privateFlags.setInt(attrs, flags and PRIVATE_FLAG_INTERCEPT_GLOBAL_DRAG_AND_DROP.inv())
                    Hooks.info("feed window: dropped global drag-and-drop interception")
                }
            }
            chain.proceed()
        }
    }
}
