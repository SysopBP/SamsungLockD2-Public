package app.d2lock.xposed

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Observes successful framework calls. Never changes credentials, flags or timeout scheduling. */
internal object CredentialObserver {
    @Volatile private var scheduler = "not yet observed"
    @Volatile private var request = "not yet observed"
    private val schedules = AtomicLong()
    private val requests = AtomicLong()
    private val pending = AtomicBoolean()
    private var worker: Handler? = null
    @Volatile private var context: Context? = null

    fun install(module: XposedModule, loader: ClassLoader) {
        worker = Handler(HandlerThread("D2CredentialEvidence").apply { start() }.looper)
        for (name in listOf("rescheduleStrongAuthTimeoutAlarm", "requireStrongAuth")) {
            var state = "unavailable"
            try {
                val owner = loader.loadClass("com.android.server.locksettings.LockSettingsStrongAuth")
                val methods = owner.declaredMethods.filter {
                    it.name == name && it.returnType == Void.TYPE &&
                        !java.lang.reflect.Modifier.isStatic(it.modifiers)
                }
                if (methods.isNotEmpty()) {
                    var installed = 0
                    for (method in methods) {
                        try {
                            method.isAccessible = true
                            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                                val result = chain.proceed()
                                // No argument interpretation: OEM signatures vary. Counts cover all users.
                                if (name == "requireStrongAuth") requests.incrementAndGet() else schedules.incrementAndGet()
                                enqueue()
                                result
                            }
                            installed++
                        } catch (_: Throwable) { state = "failed" }
                    }
                    if (installed == methods.size) state = "installed"
                    else if (installed > 0) state = "partially installed / failed"
                }
            } catch (_: ClassNotFoundException) { state = "unavailable" }
            catch (_: Throwable) { state = "failed" }
            if (name == "requireStrongAuth") request = state else scheduler = state
        }
    }
    fun attach(intent: Intent) {
        intent.putExtra("credential_scheduler", scheduler)
            .putExtra("credential_request", request)
            .putExtra("credential_schedules", schedules.get())
            .putExtra("credential_requests", requests.get())
    }
    fun ready(app: Context) { context = app; enqueue() }
    private fun enqueue() {
        if (context == null || !pending.compareAndSet(false, true)) return
        if (worker?.postDelayed({
            pending.set(false)
            runCatching {
                val app = context ?: return@runCatching
                val intent = Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(android.content.ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                    .putExtra("event", "CREDENTIAL_DIAGNOSTIC")
                    .putExtra("source", "LockSettingsStrongAuth")
                attach(intent)
                app.sendBroadcast(intent)
            }
        }, 1500L) != true) pending.set(false)
    }
}
