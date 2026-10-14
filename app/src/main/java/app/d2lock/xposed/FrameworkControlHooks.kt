/* SPDX-License-Identifier: AGPL-3.0-only */
package app.d2lock.xposed

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

/** Optional after-call observers. No framework arguments, results or authentication flags change. */
object FrameworkControlHooks {
    @Volatile private var context: Context? = null
    private val worker=java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var cached=JSONObject()
    @Volatile private var checkedAt=0L
    private val last=java.util.concurrent.ConcurrentHashMap<String,Long>()
    fun ready(c: Context) { context=c }
    private fun enabled(module: XposedModule,key: String): Boolean = runCatching {
        val now=SystemClock.elapsedRealtime()
        if(now-checkedAt>5000) synchronized(this) {
            if(now-checkedAt>5000) {
                cached=android.os.ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile("preference.json")).use {
                    JSONObject(it.readBytes().toString(Charsets.UTF_8)).optJSONObject("d2Framework") ?: JSONObject()
                };checkedAt=now
            }
        }
        cached.optBoolean("master") && cached.optBoolean(key)
    }.getOrDefault(false)
    private fun emit(module: XposedModule,key: String,event: String,method: String) {
        if(!enabled(module,key)) return
        val c=context ?: return
        val now=SystemClock.elapsedRealtime()
        synchronized(last) { if(now-(last[event] ?: 0)<1000) return;last[event]=now }
        worker.execute { runCatching {
            c.sendBroadcast(Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT").setPackage("app.d2lock")
                .putExtra("event",event).putExtra("method",method).putExtra("source","d2_framework_optin")
                .putExtra("experimental_control",true))
        }.onFailure { Log.w("D2Framework","SIGNAL_FAILED event=$event",it) } }
    }
    fun install(module: XposedModule,loader: ClassLoader) {
        fun observe(owner: String,name: String,key: String,event: String,filter: (java.lang.reflect.Method)->Boolean,send: (List<Any?>)->Boolean={true}) {
            runCatching {
                val methods=loader.loadClass(owner).declaredMethods.filter { it.name==name && filter(it) }
                check(methods.isNotEmpty()) { "No matching method" }
                methods.forEach { method ->
                    module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        val result=chain.proceed()
                        runCatching { if(send(chain.args.toList())) emit(module,key,event,"$owner#$name") }
                        result
                    }
                }
                Log.i("D2Framework","OBSERVER_INSTALLED event=$event method=$owner#$name")
            }.onFailure { Log.w("D2Framework","OBSERVER_UNAVAILABLE event=$event stock_retained=true",it) }
        }
        observe("com.android.server.power.PowerManagerService","goToSleepInternal","screen","SLEEP",{ it.returnType==Void.TYPE })
        observe("com.android.server.am.ActivityManagerService","setKeyguardShown","relock","KEYGUARD",{
            it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType))
        },{it.firstOrNull()==true})
        observe("com.android.server.wm.ActivityTaskManagerService","activityResumed","lifecycle","NATIVE_RESUMED",{
            it.parameterTypes.contentEquals(arrayOf(android.os.IBinder::class.java))
        })
    }
}
