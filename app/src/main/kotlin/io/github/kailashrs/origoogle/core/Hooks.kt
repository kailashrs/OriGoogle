package io.github.kailashrs.origoogle.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Thin helpers over the libxposed API 102 interceptor chain. */
object Hooks {
    private const val TAG = "OriGoogle"

    @Volatile
    lateinit var module: XposedModule
        private set

    fun attach(value: XposedModule) {
        module = value
    }

    fun hook(method: Executable, hooker: XposedInterface.Hooker): XposedInterface.HookHandle {
        method.isAccessible = true
        return module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept(hooker)
    }

    /** Hooks every declared method named [name]; returns how many were hooked. */
    fun hookAll(type: Class<*>, name: String, hooker: XposedInterface.Hooker): Int {
        var count = 0
        for (method in type.declaredMethods) {
            if (method.name != name || Modifier.isAbstract(method.modifiers)) continue
            hook(method, hooker)
            count++
        }
        if (count == 0) throw NoSuchMethodException("${type.name}#$name")
        return count
    }

    /** Invalidates ART inlining in callers so a hooked small method is always dispatched. */
    fun deoptimize(type: Class<*>, vararg names: String): Int {
        var count = 0
        for (method in type.declaredMethods) {
            if (method.name !in names || Modifier.isAbstract(method.modifiers) || Modifier.isNative(method.modifiers)) continue
            if (runCatching { module.deoptimize(method) }.getOrDefault(false)) count++
        }
        return count
    }

    fun findClass(name: String, loader: ClassLoader?): Class<*> = Class.forName(name, false, loader)

    fun findClassOrNull(name: String, loader: ClassLoader?): Class<*>? =
        runCatching { findClass(name, loader) }.getOrNull()

    fun method(type: Class<*>, name: String, vararg params: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredMethod(name, *params) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        throw NoSuchMethodException("${type.name}#$name(${params.joinToString { it.simpleName }})")
    }

    fun field(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            runCatching { current.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = current.superclass
        }
        throw NoSuchFieldException("${type.name}#$name")
    }

    fun get(target: Any, name: String): Any? = field(target.javaClass, name).get(target)

    fun set(target: Any, name: String, value: Any?) = field(target.javaClass, name).set(target, value)

    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    /** Runs one feature installer and logs, never throws into the host process. */
    inline fun install(feature: String, block: () -> Unit) {
        try {
            block()
            info("$feature: installed")
        } catch (error: Throwable) {
            error("$feature: skipped, original behavior kept", error)
        }
    }

    /**
     * OriginOS mutes logcat for its system apps, so each hooked process also appends to
     * origoogle.log in its own data directory (readable with root).
     */
    @Volatile
    var logFile: java.io.File? = null

    fun info(message: String) {
        Log.i(TAG, message)
        runCatching { module.log(Log.INFO, TAG, message) }
        append("I", message, null)
    }

    fun error(message: String, error: Throwable? = null) {
        Log.e(TAG, message, error)
        runCatching { module.log(Log.ERROR, TAG, message, error) }
        append("E", message, error)
    }

    private fun append(level: String, message: String, error: Throwable?) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > 256 * 1024) file.delete()
            val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val trace = error?.let { "\n" + Log.getStackTraceString(it) } ?: ""
            file.appendText("$time ${android.os.Process.myPid()} $level $message$trace\n")
        }
    }
}
