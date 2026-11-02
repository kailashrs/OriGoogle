package io.github.kailashrs.origoogle.system

import android.content.Context
import android.provider.Settings
import io.github.kailashrs.origoogle.core.Features
import io.github.kailashrs.origoogle.core.Hooks

/**
 * OriginOS routes the power-key hold through com.android.server.policy.VivoInputPolicy:
 * startJoviVoiceByPowerKey() fires after 500 ms, before the AOSP long press. It is redirected to
 * the default assistant, as the overseas build does.
 */
object PowerKeyHooks {
    fun install(loader: ClassLoader) {
        val policy = Hooks.findClass("com.android.server.policy.VivoInputPolicy", loader)

        Hooks.hook(Hooks.method(policy, "startJoviVoiceByPowerKey")) { chain ->
            if (!Features.enabled(Features.POWER_ASSISTANT)) return@hook chain.proceed()
            val policyObject = chain.thisObject
            val context = Hooks.get(policyObject, "mContext") as Context
            if (Settings.Secure.getString(context.contentResolver, "assistant").isNullOrEmpty()) {
                return@hook chain.proceed()
            }
            // PhoneWindowManager.startGoogleAssist() is vivo's overseas entry: haptic + invocation type 6.
            Hooks.call(Hooks.get(policyObject, "mPhoneWindowManager")!!, "startGoogleAssist")
            true
        }
    }
}
