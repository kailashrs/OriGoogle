package io.github.kailashrs.origoogle.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.view.WindowManager
import io.github.kailashrs.origoogle.core.Hooks
import io.github.kailashrs.origoogle.system.ContextualSearchHooks
import java.lang.reflect.Proxy

/**
 * Google Discover on the leftmost home screen. The vivo launcher's left screen client
 * (HiboardOverlayClient) speaks vivo's ILauncherOverlay, an extended copy of Google's
 * com.google.android.libraries.launcherclient protocol. Its service intent is pointed at the Google
 * app's DrawerOverlayService and the returned binder is wrapped in a vivo-shaped adapter.
 */
object FeedHooks {
    private const val CLIENT = "com.vivo.jovi.remoteservice.launcherclient"
    private const val GOOGLE_OVERLAY = "com.google.android.libraries.launcherclient.ILauncherOverlay"
    private const val GOOGLE_CALLBACK = "com.google.android.libraries.launcherclient.ILauncherOverlayCallback"

    // Google ILauncherOverlay transaction codes (AIDL order).
    private const val START_SCROLL = 1
    private const val ON_SCROLL = 2
    private const val END_SCROLL = 3
    private const val WINDOW_ATTACHED = 4
    private const val WINDOW_DETACHED = 5
    private const val CLOSE_OVERLAY = 6
    private const val ON_PAUSE = 7
    private const val ON_RESUME = 8
    private const val OPEN_OVERLAY = 9
    private const val WINDOW_ATTACHED_2 = 14
    private const val SET_ACTIVITY_STATE = 16

    private const val STATE_STARTED = 1
    private const val STATE_RESUMED = 2

    private val quietCalls = setOf("asBinder", "toString", "hashCode", "equals", "onScrollEvent", "onScroll")

    /**
     * vivo's launcher (CustomLayoutContainer) expects its left screen to bracket every movement:
     * a launcher drag ends with endScrollFromLauncher, and a close the overlay starts itself runs
     * startScrollFromHiboard ... endScrollFromHiboard. Google only reports progress, so the bridge
     * synthesizes those calls; without them the launcher stays "moving" after the first open.
     */
    private class Session(val vivoCallback: Any?) {
        enum class Phase { IDLE, LAUNCHER_DRAG, LAUNCHER_RELEASED, OVERLAY_DRAG }

        @Volatile
        var phase = Phase.IDLE

        @Synchronized
        fun launcherDragStarted() {
            phase = Phase.LAUNCHER_DRAG
        }

        @Synchronized
        fun launcherReleased() {
            if (phase == Phase.LAUNCHER_DRAG) phase = Phase.LAUNCHER_RELEASED
        }

        /** Google progress (0 = launcher, 1 = feed) forwarded in vivo's pixel units. */
        @Synchronized
        fun googleProgress(progress: Float) {
            val callback = vivoCallback ?: return
            val settled = progress <= 0f || progress >= 1f
            if (phase == Phase.IDLE && !settled) {
                phase = Phase.OVERLAY_DRAG
                Hooks.call(callback, "startScrollFromHiboard")
            }
            Hooks.call(callback, "overlayScrollChanged", progress * screenWidth())
            if (!settled) return
            when (phase) {
                Phase.LAUNCHER_RELEASED -> {
                    phase = Phase.IDLE
                    Hooks.call(callback, "endScrollFromLauncher", if (progress >= 1f) 1 else 0)
                }
                Phase.OVERLAY_DRAG -> {
                    phase = Phase.IDLE
                    Hooks.call(callback, "endScrollFromHiboard")
                }
                else -> Unit
            }
        }
    }

    /** service.api.version of the Google overlay service; decides windowAttached2/setActivityState. */
    @Volatile
    private var apiVersion = 1

    fun install(loader: ClassLoader) {
        val client = Hooks.findClass("$CLIENT.HiboardOverlayClient", loader)
        Hooks.hook(Hooks.method(client, "getServiceIntent")) { googleIntent() }

        // ILauncherOverlay.Stub.asInterface is tiny and gets inlined into onServiceConnected.
        val connection = Hooks.findClass("$CLIENT.HiboardOverlayClient\$g", loader)
        Hooks.deoptimize(connection, "onServiceConnected")
        connection.declaredMethods.filter { it.name == "onNullBinding" || it.name == "onServiceConnected" }.forEach { method ->
            Hooks.hook(method) { chain ->
                Hooks.info("feed: ${method.name} ${chain.args.firstOrNull()}")
                chain.proceed()
            }
        }

        val overlay = Hooks.findClass("$CLIENT.ILauncherOverlay", loader)
        val stub = Hooks.findClass("$CLIENT.ILauncherOverlay\$Stub", loader)
        Hooks.hook(Hooks.method(stub, "asInterface", IBinder::class.java)) { chain ->
            val binder = chain.args[0] as? IBinder
            val descriptor = runCatching { binder?.interfaceDescriptor }.getOrNull()
            if (binder != null && descriptor == GOOGLE_OVERLAY) adapter(overlay, binder) else chain.proceed()
        }
    }

    private fun googleIntent(): Intent {
        val context = currentApplication()
        val intent = Intent("com.android.launcher3.WINDOW_OVERLAY")
            .setPackage(ContextualSearchHooks.GOOGLE_APP)
            .setData(
                Uri.parse("app://${context.packageName}:${Process.myUid()}").buildUpon()
                    .appendQueryParameter("v", "7")
                    .appendQueryParameter("cv", "9")
                    .build(),
            )
        Hooks.info("feed: binding Google overlay service")
        return intent
    }

    /** Read at connect time: right after boot the Google app's metadata may not resolve yet. */
    private fun loadApiVersion() {
        val intent = googleIntent()
        apiVersion = runCatching {
            currentApplication().packageManager.resolveService(intent, PackageManager.GET_META_DATA)
                ?.serviceInfo?.metaData?.getInt("service.api.version", 1) ?: 1
        }.getOrDefault(1)
        Hooks.info("feed: Google overlay api=$apiVersion")
    }

    /** A java.lang.reflect.Proxy implementing vivo's ILauncherOverlay on top of Google's binder. */
    private fun adapter(overlay: Class<*>, remote: IBinder): Any {
        var state = STATE_STARTED
        var callback: Binder? = null
        var session = Session(null)
        loadApiVersion()
        Hooks.info("feed: adapter created for Google overlay")
        return Proxy.newProxyInstance(overlay.classLoader, arrayOf(overlay)) { proxy, method, args ->
            if (method.name !in quietCalls) Hooks.info("feed: vivo call ${method.name}")
            when (method.name) {
                "asBinder" -> remote
                "startScroll" -> {
                    session.launcherDragStarted()
                    transact(remote, START_SCROLL) {}
                }
                // vivo reports the drag in pixels; Google's protocol takes 0..1 progress.
                "onScroll" -> transact(remote, ON_SCROLL) { writeFloat(progress(args!![0] as Float)) }
                "endScroll" -> {
                    session.launcherReleased()
                    transact(remote, END_SCROLL) {}
                }
                // vivo forwards the finger lift as onScrollEvent(2, x, velocityX) instead of endScroll.
                "onScrollEvent" -> if (args!![0] == 2) {
                    session.launcherReleased()
                    transact(remote, END_SCROLL) {}
                } else Unit
                "windowAttached" -> {
                    val params = args!![0]?.let { Hooks.get(it, "mLayoutParams") as? WindowManager.LayoutParams }
                    session = Session(args[1])
                    callback = callbackBridge(session)
                    attach(remote, params, callback!!, state)
                }
                "windowDetached" -> transact(remote, WINDOW_DETACHED) { writeInt(if (args!![0] == true) 1 else 0) }
                "closeOverlay" -> transact(remote, CLOSE_OVERLAY) { writeInt(args!![0] as Int) }
                "openOverlay" -> transact(remote, OPEN_OVERLAY) { writeInt(args!![0] as Int) }
                "onResume" -> {
                    state = state or STATE_RESUMED
                    activityState(remote, state, ON_RESUME)
                }
                "onPause" -> {
                    state = state and STATE_RESUMED.inv()
                    activityState(remote, state, ON_PAUSE)
                }
                "toString" -> "OriGoogleFeedAdapter"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun attach(remote: IBinder, params: WindowManager.LayoutParams?, callback: Binder, state: Int) {
        if (params == null) return
        val context = currentApplication()
        if (apiVersion < 3) {
            transact(remote, WINDOW_ATTACHED) {
                writeInt(1)
                params.writeToParcel(this, 0)
                writeStrongBinder(callback)
                writeInt(1) // client_options: feed enabled
            }
        } else {
            val bundle = Bundle().apply {
                putParcelable("layout_params", params)
                putParcelable("configuration", context.resources.configuration)
                putInt("client_options", 1)
            }
            transact(remote, WINDOW_ATTACHED_2) {
                writeInt(1)
                bundle.writeToParcel(this, 0)
                writeStrongBinder(callback)
            }
        }
        activityState(remote, state, if (state and STATE_RESUMED != 0) ON_RESUME else ON_PAUSE)
    }

    private fun activityState(remote: IBinder, state: Int, legacyCode: Int) {
        if (apiVersion >= 4) transact(remote, SET_ACTIVITY_STATE) { writeInt(state) }
        else transact(remote, legacyCode) {}
    }

    /** Google's callback binder forwarding to vivo's ILauncherOverlayCallback through the session. */
    private fun callbackBridge(session: Session): Binder = object : Binder() {
        init {
            attachInterface(null, GOOGLE_CALLBACK)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(GOOGLE_CALLBACK)
                return true
            }
            if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(GOOGLE_CALLBACK)
            val target = session.vivoCallback ?: return true
            runCatching {
                if (code == 1) session.googleProgress(data.readFloat())
                else {
                    val status = data.readInt()
                    Hooks.info("feed: Google status $status")
                    Hooks.call(target, "overlayStatusChanged", status)
                    // vivo's left page shows "loading" until its own data-ready callback arrives.
                    if (status and 1 != 0) Hooks.call(target, "hiboardDataPrepared")
                }
            }.onFailure { Hooks.error("feed callback failed", it) }
            return true
        }
    }

    private fun transact(remote: IBinder, code: Int, write: Parcel.() -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(GOOGLE_OVERLAY)
            data.write()
            remote.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (error: Exception) {
            Hooks.error("feed transaction $code failed", error)
        } finally {
            data.recycle()
        }
    }

    private fun screenWidth(): Int = currentApplication().resources.displayMetrics.widthPixels.coerceAtLeast(1)

    /** vivo's launcher and left screen exchange scroll positions in pixels of the screen width. */
    private fun progress(pixels: Float): Float = (pixels / screenWidth()).coerceIn(0f, 1f)

    private fun currentApplication(): Application =
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Float.TYPE -> 1f
        java.lang.Long.TYPE -> 0L
        String::class.java -> ""
        java.util.List::class.java -> ArrayList<Any>()
        else -> null
    }
}
