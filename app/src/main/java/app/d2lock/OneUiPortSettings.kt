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
        check(json.parseToJsonElement(read) == json.parseToJsonElement(text)) { "Remote settings read-back failed" }
    }
    private fun bounds(key: String): Pair<Double,Double> = when {
        key=="maxNeverKilledAppNum" -> 1.0 to 20.0
        key=="watchPairingConnectionMode" -> 0.0 to 2.0
        key=="networkSpeedLayout" -> 0.0 to 3.0
        key=="networkSpeedMarker" -> 0.0 to 8.0
        key=="networkSpeedUnit" -> 0.0 to 3.0
        key=="physicalEsimAdapterSimSlot" -> 0.0 to 1.0
        key=="statusBarMaxNotificationIcons" -> 0.0 to 20.0
        key.contains("Scale") -> 0.25 to 3.0
        key.contains("TextSize") || key.contains("MarkerSize") -> 0.0 to 64.0
        key.contains("Padding") || key.contains("Gap") || key.contains("Spacing") -> 0.0 to 100.0
        else -> 0.0 to 10000.0
    }
    private fun label(key: String): String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }
    private val groups = linkedMapOf(
        "Framework" to "android", "Calls" to "call", "Samsung Settings" to "settings", "Camera" to "camera",
        "Status bar" to "systemUI.statusBar", "Quick Settings" to "systemUI.qs", "Always On Display" to "systemUI.aod",
        "Notifications" to "systemUI.notification", "Power menu and screenshots" to "systemUI.other", "Browser and video" to "other", "Gallery and AI editing" to "other", "Samsung Notes" to "other",
        "Messaging" to "other", "Health Monitor" to "other", "Launcher" to "other", "Dual apps" to "other",
        "Watch pairing" to "other", "Calendar and weather" to "other", "Galaxy Store and S Pen" to "other"
    )
    private fun node(root: JSONObject, path: String): JSONObject = path.split('.').fold(root) { value, key -> value.getJSONObject(key) }
    fun show(activity: Activity) {
        init(activity)
        val state = JSONObject(prefs(activity).getString("config", defaults())!!)
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24,16,24,16) }
        val status = TextView(activity).apply {
            text = "Experimental OneUI features • Android 17 testing required\nEnable D2 in LSPosed for the selected feature's app scope. Apply saves the configuration; soft reboot once after framework changes, or restart the affected Samsung app for app features. Saved does not mean the hook exists on this firmware.\n\nD2's SRP-guarded power menu and 72-hour PIN/pattern diagnostics remain separate. Galaxy Island is optional."
        }; body.addView(status)
        val guide=status.text.toString()
        status.text="Choose features below. Apply saves through LSPosed; soft reboot after framework changes. Samsung compatibility remains unverified until device testing."
        body.addView(Button(activity).apply { text="Setup and compatibility guide"; setOnClickListener { FeatureGlass.help(activity,"OneUI setup",guide) } })
        body.addView(Button(activity).apply { text="Custom charging limit"; setOnClickListener { ChargingLimitSettings.show(activity) } })
        body.addView(Switch(activity).apply { text="Enable OneUI feature port"; isChecked=state.optBoolean("d2PortEnabled"); setOnCheckedChangeListener { _, v -> state.put("d2PortEnabled",v) } })
        body.addView(TextView(activity).apply { text="Add D2 5G and D2 immersive mode through Quick Settings → Edit tiles. The 5G tile uses the active data SIM and requires root. Both tiles report unavailable if their device APIs fail." })
        body.addView(Button(activity).apply { text="72-hour PIN / pattern diagnostics"; setOnClickListener { PeriodicCredentialSettings.show(activity) } })
        val search=EditText(activity).apply { hint="Search features or categories";isSingleLine=true };body.addView(search)
        val categoryButtons=mutableListOf<Pair<Button,String>>()
        groups.forEach { (title,path) ->
            val b=Button(activity).apply { text=title; setOnClickListener { showGroup(activity,title,path,node(state,path)) } }
            body.addView(b);categoryButtons.add(b to (title+" "+node(state,path).keys().asSequence().filter { path!="other" || it in otherKeys(title) }.joinToString(" ") { it+" "+label(it) }))
        }
        search.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?,start: Int,count: Int,after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?,start: Int,before: Int,count: Int) {
                val q=s.toString().trim();categoryButtons.forEach { (b,terms) -> b.visibility=if(terms.contains(q,true)) android.view.View.VISIBLE else android.view.View.GONE }
            }
        })
        body.addView(TextView(activity).apply { text="Camera temperature-check removal reduces a thermal safeguard. Health Monitor country support and Notes AI menus do not guarantee server access or medical-device availability. Phone status spoofing changes displayed status only. Emergency actions remain in D2's power menu." })
        val dialog=AlertDialog.Builder(activity).setTitle("OneUI feature port").setView(ScrollView(activity).apply { addView(body) })
            .setPositiveButton("Apply",null).setNegativeButton("Close",null).create()
        dialog.setOnShowListener {
            FeatureGlass.style(activity,dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val bound=service
                val text=state.toString()
                // Validate against the upstream schema before publishing any bytes.
                runCatching { json.decodeFromString(Preference.serializer(),text) }.getOrElse {
                    val message="Invalid configuration: ${it.message}. Changes have not been saved."
                    status.text=message
                    FeatureGlass.help(activity,"Could not save",message)
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=false
                val app=activity.applicationContext
                worker.execute {
                    // Preserve the user's selection before attempting the remote service.
                    // A missing service or failed read-back must never discard it.
                    val saved=runCatching {
                        check(prefs(app).edit().putString("config",text).commit()) { "Local settings write failed" }
                        check(json.parseToJsonElement(prefs(app).getString("config", "")!!) == json.parseToJsonElement(text)) { "Local settings read-back failed" }
                    }
                    val applied=if(saved.isSuccess) runCatching {
                        val active=checkNotNull(bound) { "LSPosed module service unavailable" }
                        publish(active,export(app,text))
                    } else null
                    activity.runOnUiThread { if(dialog.isShowing) {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled=true
                        val title=when {
                            saved.isFailure -> "Could not save"
                            applied?.isSuccess==true -> "Settings applied"
                            else -> "Settings saved — pending LSPosed"
                        }
                        val message=when {
                            saved.isFailure -> "${saved.exceptionOrNull()?.message}. Changes have not been applied."
                            applied?.isSuccess==true -> "Saved locally and verified through LSPosed. Soft reboot after framework changes, then unlock Samsung once. Hook compatibility still requires device verification."
                            else -> "Your selections are saved and will remain after reopening D2 or rebooting. ${applied?.exceptionOrNull()?.message}. Enable D2 in LSPosed and reopen D2, then tap Apply again. Wait for Settings applied before soft rebooting."
                        }
                        status.text=message
                        FeatureGlass.help(activity,title,message)
                    } }
                }
            }
        };dialog.show()
    }
    private fun otherKeys(title: String): Set<String> = when(title) {
            "Browser and video" -> setOf("showMorePlaybackSpeeds","redirectCustomTab","spoofBrowserCountryCodeToUS")
            "Gallery and AI editing" -> setOf("supportAllGallerySettings","supportSharedAlbumsInHide","hideVideoEditorStudio","noAIWatermark")
            "Samsung Notes" -> setOf("supportAllNotesFeatures")
            "Messaging" -> setOf("supportBlockMessage")
            "Health Monitor" -> setOf("bypassHealthMonitorCountryCheck")
            "Launcher" -> setOf("showMemoryUsageInRecents","recentsGridThreeRows","hideRecentsCloseAllButton","hideAppsSearchBar","removeShortcutBadge")
            "Dual apps" -> setOf("makeAllUserAppsAvailable")
            "Watch pairing" -> setOf("bypassWatchPairingRegionCheck","watchPairingConnectionMode","supplementChinaWearOsGms")
            "Calendar and weather" -> setOf("enableChineseHolidayDisplay","setWeatherProviderCN")
            else -> setOf("blockGalaxyStoreAds","useSPenGoogleTranslate")
        }
    private fun showGroup(a: Activity,title: String,path: String,state: JSONObject) {
        val body=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(24,12,24,12) }
        val excluded=setOf("powerMenuActions","setThemeTrialNeverExpired")
        if(path=="android") body.addView(TextView(a).apply {
            text="EXPERIMENTAL: This OneUIX 1.9.0 option suppresses periodic StrongAuth timeout rescheduling. It weakens periodic PIN/pattern reauthentication and may not affect existing alarms or other Samsung authentication requirements. Leave disabled unless intentionally testing. Tap Apply and reboot to test; do not assume success without device validation."
        })
        val otherAllowed=otherKeys(title)
        state.keys().asSequence().toList().forEach { key ->
            if(key in excluded || (path=="other" && key !in otherAllowed)) return@forEach
            val v=state.get(key)
            if(v is Boolean) body.addView(Switch(a).apply { text=if(key=="disablePinVerifyPer72h") "OneUIX 72-hour PIN / pattern suppression (experimental)" else label(key); isChecked=v;
                if(key=="turnOn5gQsTile" && android.os.Build.VERSION.SDK_INT!=34) { isEnabled=false; text="Native 5G tile: Android 14 only" }
                setOnCheckedChangeListener { _,checked -> state.put(key,checked) } })
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
                                val (min,max)=bounds(key)
                                if(n!=null && n.isFinite() && n>=min && n<=max && (v !is Int || n==n.toInt().toDouble())) {
                                    error=null;state.put(key,if(v is Int) n.toInt() else n)
                                } else error="Use $min–$max; previous valid value retained"
                            }
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    })
                })
            }
        }
        body.addView(TextView(a).apply { text="Close this panel, then Apply in the feature port screen. Options whose Samsung classes are absent retain stock behavior. Network layout: 0 system, 1 horizontal, 2 vertical, 3 active direction; SIM slot: 0 or 1. Watch mode: 0 stock, 1 China Wear OS, 2 global Wear OS. Scale 1.0 = 100%." })
        if(path=="systemUI.other") body.addView(Button(a).apply { text="Power-menu action order and visibility";setOnClickListener { PowerActionSettings.show(a,state) } })
        val d=AlertDialog.Builder(a).setTitle(title).setView(ScrollView(a).apply { addView(body) }).setPositiveButton("Done",null).create()
        d.setOnShowListener { FeatureGlass.style(a,d) };d.show()
    }
}
