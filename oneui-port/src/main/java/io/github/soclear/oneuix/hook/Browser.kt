/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ b8f81448b2bcaa314c0cb8abec0c8ff527a14e27. */
package io.github.soclear.oneuix.hook

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.hook.util.afterAttachTry
import io.github.soclear.oneuix.hook.util.reflect
import io.github.soclear.oneuix.hook.util.xlog

object Browser {
    context(xposedModule: XposedModule)
    fun showMorePlaybackSpeeds() = afterAttachTry {
        // Restore visibility of playback speed feature
        runCatching {
            classLoader
                .loadClass("com.sec.android.app.sbrowser.common.device.setting_preference.SettingPreference")
                .getDeclaredMethod("getPlaybackRateViewVisibility")
        }.getOrElse {
            return@afterAttachTry
        }.let {
            xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { true }
        }

        xposedModule.hook(
            classLoader
                .loadClass($$"com.sec.android.app.sbrowser.media.common.MediaFeatureGlobalConfigUtils$Companion")
                .getDeclaredMethod("isPlaybackSpeedEnabled", Context::class.java)
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { true }

        val playbackRateViewClass =
            classLoader.loadClass("${Package.BROWSER}.media.player.fullscreen.view.MPFullScreenPlaybackRateView")
        val sPlaybackRates = doubleArrayOf(
            0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0
        )
        xposedModule.hook(
            playbackRateViewClass.getDeclaredMethod("init", LinearLayout::class.java)
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            fun setPlaybackRates() {
                playbackRateViewClass.reflect["sPlaybackRates"] = sPlaybackRates
            }

            fun createRadioButton(text: String, template: RadioButton): RadioButton {
                return RadioButton(template.context).apply {
                    id = View.generateViewId()
                    this.text = text
                    textSize = 12f
                    setTextColor(template.currentTextColor)
                    buttonDrawable = template.buttonDrawable
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    typeface = template.typeface
                    setPadding(0, 0, 0, 0)
                    layoutParams = RadioGroup.LayoutParams(template.layoutParams).apply {
                        weight = 1f
                        setMargins(0, 0, 0, 0)
                    }
                }
            }

            fun addMoreSpeeds() {
                val radioGroup =
                    chain.thisObject.reflect["mPlaybackSpeedRadioGroup"] as? RadioGroup ?: return
                val radioButton = radioGroup.getChildAt(0) as? RadioButton ?: return
                radioGroup.addView(createRadioButton("3.0", radioButton))
                radioGroup.addView(createRadioButton("4.0", radioButton))
            }

            fun setPlaybackRateViewWidth() {
                val view = chain.thisObject.reflect["mPlaybackRateView"] as? View ?: return

                val density = view.resources.displayMetrics.density
                view.layoutParams = view.layoutParams.apply {
                    width = (380 * density).toInt()
                }
            }

            val result = chain.proceed()
            try {
                setPlaybackRates()
                setPlaybackRateViewWidth()
                addMoreSpeeds()
            } catch (t: Throwable) {
                xlog(t)
            }
            result
        }


        xposedModule.hook(
            playbackRateViewClass.getDeclaredMethod("setInitialSpeed")
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            try {
                val mController = chain.thisObject.reflect["mController"] ?: throw Exception("mController is null")
                val speed = mController.reflect.call("getPlaybackRate") as Double
                var index = sPlaybackRates.indexOfFirst { it == speed }
                // Default to 1.0x
                if (index == -1) index = 3

                val radioGroup =
                    chain.thisObject.reflect["mPlaybackSpeedRadioGroup"] as? RadioGroup ?: return@intercept null
                val radioButton =
                    radioGroup.getChildAt(index) as? RadioButton ?: return@intercept null

                mController.reflect.call("setPlaybackRate", speed)
                chain.thisObject.reflect["mCurrentSpeed"] = radioButton
                radioButton.isChecked = true
                chain.thisObject.reflect.call("highlightSelectedSpeedButton", radioButton)
            } catch (t: Throwable) {
                xlog(t)
            }
            null
        }
    }

    context(xposedModule: XposedModule)
    fun setCountryIsoCode(code: String) = afterAttachTry {
        runCatching {
            classLoader
                .loadClass("com.sec.android.app.sbrowser.common.application.AppInfo")
                .getDeclaredMethod("isCnApk")
        }.getOrElse {
            return@afterAttachTry
        }.let { xposedModule.hook(it).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { code == "CN" } }

        xposedModule.hook(
            classLoader
                .loadClass("com.sec.android.app.sbrowser.common.device.CountryUtil")
                .getDeclaredMethod("getCountryIsoCode")
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { code }

        xposedModule.hook(
            classLoader
                .loadClass("com.sec.android.app.sbrowser.common.device.SystemProperties")
                .getDeclaredMethod("getCountryCodeintoLocaleForGED")
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { code }

        xposedModule.hook(
            classLoader
                .loadClass("com.sec.android.app.sbrowser.common.device.SystemProperties")
                .getDeclaredMethod("getCscCountryIsoCode")
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { code }
    }

    context(xposedModule: XposedModule)
    fun redirectCustomTab() = afterAttachTry {
        xposedModule.hook(
            classLoader
                .loadClass("com.sec.android.app.sbrowser.customtabs.CustomTabActivity")
                .getDeclaredMethod("onCreate", Bundle::class.java)
        ).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
            val activity = chain.thisObject as Activity
            val originalIntent = activity.intent ?: return@intercept chain.proceed()
            val uri = originalIntent.data ?: return@intercept chain.proceed()
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return@intercept chain.proceed()
            }
            val cleanIntent = Intent(originalIntent).apply {
                component = null
                `package` = packageName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                extras?.keySet()?.filter { key ->
                    key.startsWith("androidx.browser.customtabs.extra.") ||
                            key.startsWith("android.support.customtabs.extra.") ||
                            key.startsWith("org.chromium.chrome.browser.customtabs.")
                }?.forEach(::removeExtra)
                putExtra("com.android.browser.application_id", packageName)
                putExtra("create_new_tab", true)
            }
            activity.startActivity(cleanIntent)
            activity.finish()
            originalIntent.data = null
            chain.proceed()
        }
    }
}
