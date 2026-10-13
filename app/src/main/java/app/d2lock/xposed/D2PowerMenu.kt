package app.d2lock.xposed

import android.app.AlertDialog
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import java.lang.reflect.Proxy
import java.util.UUID

/** Samsung hosts the actions and confirmations; D2 adds guarded soft restart and live status. */
internal object D2PowerMenu {
    private const val TAG = "D2PowerMenu"
    const val SOFT_RESTART_ICON = 0x7ed20001
    const val SRP_STATUS_ICON = 0x7ed20002

    /** Draw directly in SystemUI: module resource IDs belong to a different APK. */
    fun icon(id: Int, density: Float): Drawable? {
        if (id != SOFT_RESTART_ICON && id != SRP_STATUS_ICON) return null
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.8f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            override fun draw(canvas: Canvas) {
                val save = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                if (id == SOFT_RESTART_ICON) {
                    canvas.drawArc(4f, 4f, 20f, 20f, 45f, 285f, false, paint)
                    canvas.drawPath(Path().apply {
                        moveTo(16f, 3f); lineTo(20f, 7f); lineTo(20f, 2f)
                    }, paint)
                    canvas.drawPath(Path().apply {
                        moveTo(13f, 8f); lineTo(10f, 12f); lineTo(14f, 12f); lineTo(11f, 16f)
                    }, paint)
                } else {
                    canvas.drawPath(Path().apply {
                        moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 11f)
                        cubicTo(20f, 16f, 16f, 19.5f, 12f, 21.5f)
                        cubicTo(8f, 19.5f, 4f, 16f, 4f, 11f)
                        lineTo(4f, 5.5f); close()
                    }, paint)
                    canvas.drawPath(Path().apply {
                        moveTo(8f, 11.5f); lineTo(11f, 14.5f); lineTo(16f, 9f)
                    }, paint)
                }
                canvas.restoreToCount(save)
            }
            override fun getIntrinsicWidth() = (32f * density).toInt()
            override fun getIntrinsicHeight() = (32f * density).toInt()
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
    fun context(): Context = Class.forName("android.app.ActivityThread")
        .getDeclaredMethod("currentApplication").invoke(null) as Context

    private fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
        return method.invoke(target, *args)
    }

    fun addActions(presenter: Any, loader: ClassLoader) {
        val actionInterface = loader.loadClass("com.samsung.android.globalactions.presentation.viewmodel.ActionViewModel")
        val infoClass = loader.loadClass("com.samsung.android.globalactions.presentation.viewmodel.ActionInfo")
        val viewType = loader.loadClass("com.samsung.android.globalactions.presentation.viewmodel.ViewType")
            .enumConstants!!.first { (it as Enum<*>).name == "CENTER_ICON_3P_VIEW" }
        fun action(name: String, title: String, icon: Int, press: (Any) -> Unit): Any {
            val info = infoClass.getDeclaredConstructor().newInstance()
            call(info, "setName", name)
            call(info, "setLabel", title)
            call(info, "setDescription", if (name == "d2_soft_restart") "D2 SRP verification required" else "Fresh System Server vault check")
            call(info, "setIcon", icon)
            call(info, "setViewType", viewType)
            return Proxy.newProxyInstance(loader, arrayOf(actionInterface)) { proxy, method, args ->
                when (method.name) {
                    "getActionInfo" -> info
                    "setActionInfo" -> null
                    "onPress" -> { runCatching { press(proxy) }.onFailure { Log.e(TAG, "D2_POWER_ACTION_FAILED name=$name", it) }; null }
                    "isAvailableShow" -> true
                    "getLongPressTime" -> 500
                    "getState" -> method.returnType.enumConstants?.firstOrNull { (it as Enum<*>).name == "off" }
                    "toString" -> "D2PowerAction($name)"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        java.lang.Double.TYPE -> 0.0
                        java.lang.Short.TYPE -> 0.toShort()
                        java.lang.Byte.TYPE -> 0.toByte()
                        java.lang.Character.TYPE -> '\u0000'
                        else -> null
                    }
                }
            }
        }
        // Build every replacement before changing the list. Keep Samsung's real power models
        // so native permission, credential confirmation and long-press behavior stay intact.
        val factoryField = generateSequence(presenter.javaClass as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mViewModelFactory") }.getOrNull() }.first()
            .apply { isAccessible = true }
        val factory = factoryField.get(presenter) ?: error("Samsung action factory unavailable")
        fun stock(name: String, label: String): Any {
            val model = call(factory, "createActionViewModel", presenter, name) ?: error("Missing Samsung $name action")
            val info = call(model, "getActionInfo") ?: error("Missing Samsung action info")
            call(info, "setLabel", label)
            call(info, "setStateLabel", "D2 SRP checked before continuing")
            return model
        }
        val power = stock("power", "D2 power off")
        val restart = stock("restart", "D2 restart")
        // Preserve stock emergency, lockdown, safe-mode and other unrelated actions.
        val soft = action("d2_soft_restart", "D2 soft restart", SOFT_RESTART_ICON) { proxy ->
            if (call(presenter, "isActionConfirming") != true) {
                call(presenter, "confirmAction", proxy)
            } else {
                call(presenter, "dismissDialog", false)
                val app = context()
                if (Build.VERSION.SDK_INT >= 34) app.sendBroadcast(
                    Intent("app.d2lock.action.DMC_SOFT_RESTART").setPackage("android"), null,
                    BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            }
        }
        val status = action("d2_dmc_status", "D2 SRP status", SRP_STATUS_ICON) {
            // Samsung's global-actions window sits above KEYGUARD_DIALOG windows.
            // Close it before presenting status, allowing its exit animation to finish.
            call(presenter, "dismissDialog", false)
            Handler(Looper.getMainLooper()).postDelayed({ showStatus(context()) }, 350L)
        }
        call(presenter, "clearActions", "power")
        call(presenter, "clearActions", "restart")
        call(presenter, "clearActions", "d2_soft_restart")
        call(presenter, "clearActions", "d2_dmc_status")
        call(presenter, "addAction", power)
        call(presenter, "addAction", restart)
        call(presenter, "addAction", soft)
        call(presenter, "addAction", status)
        Log.i(TAG, "D2_POWER_MENU_READY actions=d2_power_restart_soft_restart_and_status")
    }

    private fun showStatus(app: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val handler = Handler(Looper.getMainLooper())
        val dialog = AlertDialog.Builder(app)
            .setTitle("D2 SRP")
            .setMessage("Samsung Recovery Protection\n\nChecking the Samsung recovery vault inside System Server…")
            .setPositiveButton("Close", null).create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG)
        val reply = "app.d2lock.action.DMC_RESULT." + UUID.randomUUID()
        var registered = false
        var received = false
        lateinit var receiver: BroadcastReceiver
        fun cleanup() {
            if (registered) { registered = false; runCatching { app.unregisterReceiver(receiver) } }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (sentFromUid != 1000) return
                received = true
                cleanup()
                val verified = intent.getBooleanExtra("verified", false)
                fun flag(key: String, target: Int) = if (verified && intent.getIntExtra(key, -1) == target) "✓" else "?"
                dialog.setMessage("${if (verified) "✓ Vault targets verified" else "? Vault unverified"}\n\n${flag("lock", 0)} SRP screen-lock flag: disabled\n${flag("maint", 0)} Maintenance Mode flag: disabled\n${flag("at", 1)} Download AT-command flag: enabled\n\n${intent.getStringExtra("detail") ?: "No details"}\n\nSamsung Recovery Protection (D2 SRP) performs a fresh check for every guarded power action. This does not guarantee firmware persistence.")
            }
        }
        try {
            app.registerReceiver(receiver, IntentFilter(reply), Context.RECEIVER_EXPORTED)
            registered = true
            dialog.setOnDismissListener { cleanup() }
            dialog.show()
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 32f * app.resources.displayMetrics.density
                setColor(Color.rgb(25, 25, 30))
                setStroke((app.resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.argb(90, 255, 255, 255))
            })
            dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(Color.WHITE)
            val titleId = app.resources.getIdentifier("alertTitle", "id", "android")
            dialog.findViewById<android.widget.TextView>(titleId)?.setTextColor(Color.WHITE)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.WHITE)
            app.sendBroadcast(Intent("app.d2lock.action.DMC_REQUEST").setPackage("android").putExtra("reply", reply), null,
                BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            handler.postDelayed({
                cleanup()
                if (!received && dialog.isShowing) dialog.setMessage("? System Server did not respond. Enable D2 in LSPosed System Framework and SystemUI scopes, then reboot. Protection is unverified.")
            }, 6_000L)
        } catch (error: Throwable) {
            cleanup()
            runCatching { dialog.dismiss() }
            Log.e(TAG, "D2_POWER_STATUS_FAILED", error)
        }
    }
}
