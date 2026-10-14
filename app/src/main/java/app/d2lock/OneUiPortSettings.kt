/* SPDX-License-Identifier: AGPL-3.0-only; D2 adaptation, 2026-10-14. */
package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.ParcelFileDescriptor
import android.widget.*
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.soclear.oneuix.common.Preference
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Private app settings exported only through libxposed's module remote-file service. */
object OneUiPortSettings {
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var service: XposedService? = null
    private var initialized = false
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("oneui_port", Context.MODE_PRIVATE)
    @Synchronized fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(s: XposedService) {
                service = s
                worker.execute { runCatching { publish(s, export(app,prefs(app).getString("config", defaults())!!)) } }
            }
            override fun onServiceDied(s: XposedService) { if (service === s) service = null }
        })
    }
    private fun export(context: Context,text: String): String = JSONObject(text).put("d2Framework", JSONObject()
        .put("master",Prefs.xposedMaster(context)).put("screen",Prefs.xposedScreenAwareness(context))
        .put("relock",Prefs.xposedImmediateRelock(context)).put("lifecycle",Prefs.xposedNativeLifecycle(context))).toString()
    fun syncFramework(context: Context) {
        init(context)
        val app=context.applicationContext
        worker.execute { service?.let { s -> runCatching { publish(s,export(app,prefs(app).getString("config",defaults())!!)) } } }
    }
    private fun defaults(): String = JSONObject(json.encodeToString(Preference.serializer(), Preference()))
        .put("d2PortEnabled", false).toString()
    private fun publish(s: XposedService, text: String) {
        ParcelFileDescriptor.AutoCloseOutputStream(s.openRemoteFile(Preference.FILE_NAME)).use { out ->
            out.channel.truncate(0); out.write(text.toByteArray(Charsets.UTF_8)); out.channel.force(true)
        }
        val read = ParcelFileDescriptor.AutoCloseInputStream(s.openRemoteFile(Preference.FILE_NAME)).use { it.readBytes().toString(Charsets.UTF_8) }
        check(JSONObject(read).toString() == JSONObject(text).toString()) { "Remote settings read-back failed" }
    }
    private fun label(key: String): String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
    private val groups = linkedMapOf(
        "Framework" to "android", "Calls" to "call", "Samsung Settings" to "settings", "Camera" to "camera",
        "Status bar" to "systemUI.statusBar", "Quick Settings" to "systemUI.qs", "Always On Display" to "systemUI.aod",
        "Notifications" to "systemUI.notification", "Screenshot sound" to "systemUI.other", "Notes, messaging and health" to "other"
    )
    private fun node(root: JSONObject, path: String): JSONObject = path.split('.').fold(root) { value, key -> value.getJSONObject(key) }
    fun show(activity: Activity) {
        init(activity)
        val state = JSONObject(prefs(activity).getString("config", defaults())!!)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        val status = TextView(activity).apply {
            text = "Experimental OneUI features • Android 17 testing required\nEnable D2 in LSPosed for the selected feature's app scope. Apply saves the configuration; soft reboot once after framework changes, or restart the affected Samsung app for app features. Saved does not mean the hook exists on this firmware.\n\nD2's SRP-guarded power menu and 72-hour PIN/pattern option remain separate. Galaxy Island is optional."
        }; body.addView(status)
        body.addView(Switch(activity).apply { text="Enable OneUI feature port"; isChecked=state.optBoolean("d2PortEnabled"); setOnCheckedChangeListener { _, v -> state.put("d2PortEnabled",v) } })
        body.addView(Button(activity).apply { text="72-hour PIN / pattern option"; setOnClickListener { PeriodicCredentialSettings.show(activity) } })
        groups.forEach { (title,path) ->
            body.addView(Button(activity).apply { text=title; setOnClickListener { showGroup(activity,title,path,node(state,path)) } })
        }
        body.addView(TextView(activity).apply { text="Camera temperature-check removal reduces a thermal safeguard. Health Monitor country support and Notes AI menus do not guarantee server access or medical-device availability. Phone status spoofing changes displayed status only. Emergency actions remain in D2's power menu." })
        val dialog=AlertDialog.Builder(activity).setTitle("OneUI feature port").setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Apply",null).setNegativeButton("Close",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bound=service
                if(bound==null) { status.text="LSPosed module service unavailable. Enable D2 as a module and reopen D2. Changes have not been applied."; return@setOnClickListener }
                val text=state.toString()
                // Validate against the upstream schema before publishing any bytes.
                runCatching { json.decodeFromString(Preference.serializer(),text) }.getOrElse { status.text="Invalid configuration: ${it.message}"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                worker.execute {
                    val result=runCatching { publish(bound,export(activity,text)); check(prefs(activity).edit().putString("config",text).commit()) }
                    activity.runOnUiThread { if(dialog.isShowing) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                        status.text=if(result.isSuccess) "Saved and read back through LSPosed. Soft reboot after framework changes; restart affected Samsung apps. Hooks remain unverified until device testing." else "Could not apply: ${result.exceptionOrNull()?.message}. Retry with LSPosed connected."
                    } }
                }
            }
        };dialog.show()
    }
    private fun showGroup(a: Activity,title: String,path: String,state: JSONObject) {
        val body=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val excluded=setOf("disablePinVerifyPer72h","disableFlagSecure","customPowerMenu","powerMenuActions")
        val otherAllowed=setOf("supportAllNotesFeatures","supportBlockMessage","bypassHealthMonitorCountryCheck")
        state.keys().asSequence().toList().forEach { key ->
            if(key in excluded || (path=="other" && key !in otherAllowed)) return@forEach
            val v=state.get(key)
            if(v is Boolean) body.addView(Switch(a).apply { text=label(key); isChecked=v; setOnCheckedChangeListener { _,checked -> state.put(key,checked) } })
            else if(v is Number || v is String) {
                body.addView(TextView(a).apply { text=label(key) })
                body.addView(EditText(a).apply {
                    setText(v.toString()); isSingleLine=true
                    if(v is Number) inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    addTextChangedListener(object: android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
                        override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                            if(v is String) state.put(key,s.toString()) else {
                                val n=s.toString().toDoubleOrNull()
                                if(n!=null && n.isFinite() && n>=0 && n<=10000) state.put(key,if(v is Int) n.toInt() else n)
                            }
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                })
            }
        }
        body.addView(TextView(a).apply { text="Close this panel, then Apply in the feature port screen. Options whose Samsung classes are absent retain stock behavior. Network layout: 0 system, 1 horizontal, 2 vertical, 3 active direction; SIM slot: 0 or 1. Scale 1.0 = 100%." })
        AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(body) }).setPositiveButton("Done",null).show()
    }
}
