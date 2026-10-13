package app.d2lock.xposed

import android.app.AlertDialog
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
        val soft = action("d2_soft_restart", "D2 soft restart", android.R.drawable.ic_popup_sync) { proxy ->
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
        val status = action("d2_dmc_status", "D2 SRP status", android.R.drawable.ic_menu_info_details) {
            showStatus(context())
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
            .setMessage("Samsung Recovery Protection\n\nChecking the Samsung DMC vault inside System Server…")
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
