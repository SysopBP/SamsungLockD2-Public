package app.d2lock.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Guardian Xposed Bridge — verified minimal Android 17 boot handoff.
 *
 * Purpose:
 * - Preserve D2's UID 1000/system-app design.
 * - Keep only the proven ActivityManagerService.systemReady handoff.
 * - Observe completed credential/enrollment mutations without changing their results.
 * - Never change framework arguments/results.
 * - Fail open if the hook or D2 signal fails.
 *
 * This deliberately removes the previous diagnostic/enforcement hook surface so
 * This is the release baseline validated across five consecutive reboots on the\n * SM-S948U1 Android 17 test device. Do not expand the system_server hook surface\n * without isolated boot testing.
 */
class GuardianXposedBridge : XposedModule() {
    companion object {
        private const val TAG = "D2XposedBridge"
        private val dmcLock = Any()
        private val bootReadySent = AtomicBoolean(false)
        private val lastWakeSignalMs = AtomicLong(0L)
        private const val WAKE_DEDUPE_MS = 750L
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_MINIMAL_LOADED api=$apiVersion framework=$frameworkName mode=ams_systemReady_plus_wake_observe"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(
            Log.INFO,
            TAG,
            "GUARDIAN_MINIMAL_SYSTEM_SERVER_START uid=${android.os.Process.myUid()}"
        )
        installBootReadyHook(param.classLoader)
        installWakeObserver(param.classLoader)
        installSecuritySetupObservers(param.classLoader)
    }

    private fun installBootReadyHook(classLoader: ClassLoader) {
        val className = "com.android.server.am.ActivityManagerService"
        val owner = runCatching {
            Class.forName(className, false, classLoader)
        }.getOrElse {
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_CLASS_MISSING target=$className", it)
            return
        }

        val methods = owner.declaredMethods
            .filter { it.name == "systemReady" }
            .distinctBy { it.toGenericString() }

        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_HOOK_UNAVAILABLE target=$className#systemReady")
            return
        }

        methods.forEach { method ->
            try {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        // Android/system_server always gets control first. D2 is signalled
                        // only after the real systemReady invocation returns successfully.
                        val result = chain.proceed()
                        signalBootReady()
                        result
                    }

                log(
                    Log.INFO,
                    TAG,
                    "GUARDIAN_MINIMAL_HOOK_INSTALLED target=${owner.name}#${method.name}"
                )
            } catch (t: Throwable) {
                // Fail open: a bridge problem must never intentionally block boot.
                log(
                    Log.WARN,
                    TAG,
                    "GUARDIAN_MINIMAL_HOOK_FAILED target=${owner.name}#${method.name}",
                    t
                )
            }
        }
    }


    /**
     * Android 17 on the SM-S948U1 no longer exposes the older
     * PowerManagerService#wakeUpInternal signature. Observe the public BinderService
     * wake entry points that are actually present instead. This hook is deliberately
     * post-call and never changes arguments/results, so Android remains authoritative.
     */
    private fun installWakeObserver(classLoader: ClassLoader) {
        val className = "com.android.server.power.PowerManagerService\$BinderService"
        val owner = runCatching { Class.forName(className, false, classLoader) }.getOrElse {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_CLASS_MISSING target=$className", it)
            return
        }
        val methods = owner.declaredMethods
            .filter { it.name == "wakeUp" || it.name == "wakeUpWithDisplayId" }
            .distinctBy { it.toGenericString() }

        if (methods.isEmpty()) {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_UNAVAILABLE target=$className#wakeUp*")
            return
        }

        methods.forEach { method ->
            try {
                method.isAccessible = true
                hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        signalWakeObserved(method.name, owner.name)
                        result
                    }
                log(Log.INFO, TAG, "GUARDIAN_WAKE_HOOK_INSTALLED target=${owner.name}#${method.name}")
            } catch (t: Throwable) {
                log(Log.WARN, TAG, "GUARDIAN_WAKE_HOOK_FAILED target=${owner.name}#${method.name}", t)
            }
        }
    }

    private fun signalWakeObserved(method: String, source: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastWakeSignalMs.get()
        if (now - previous < WAKE_DEDUPE_MS || !lastWakeSignalMs.compareAndSet(previous, now)) return

        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
                ?: error("system_server application unavailable")
            app.sendBroadcast(
                Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                    .putExtra("event", "WAKE")
                    .putExtra("method", method)
                    .putExtra("source", "$source#$method")
            )
            log(Log.INFO, TAG, "GUARDIAN_WAKE_SENT method=$method")
        }.onFailure {
            log(Log.WARN, TAG, "GUARDIAN_WAKE_SIGNAL_FAILED method=$method", it)
        }
    }

    private val securityRefreshHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val securityRefreshLock = Any()
    private val securityRefresh = Runnable { refreshAfterSecuritySetup() }
    private val securityFollowUp = Runnable { refreshAfterSecuritySetup() }

    /** Observe committed setup operations only; never inspect credentials or template contents. */
    private fun installSecuritySetupObservers(classLoader: ClassLoader) {
        val targets = mapOf(
            "com.android.server.locksettings.LockSettingsService" to setOf("setLockCredential", "setLockCredentialWithToken"),
            "com.android.server.biometrics.sensors.fingerprint.FingerprintUtils" to setOf("addBiometricForUser", "removeBiometricForUser"),
            "com.android.server.biometrics.sensors.face.FaceUtils" to setOf("addBiometricForUser", "removeBiometricForUser")
        )
        targets.forEach { (className, names) ->
            runCatching {
                val owner = Class.forName(className, false, classLoader)
                val methods = owner.declaredMethods.filter { it.name in names && !it.isBridge && !it.isSynthetic }
                if (methods.isEmpty()) Log.w(TAG, "DMC_SETUP_OBSERVER_UNAVAILABLE target=$className")
                methods.forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val result = chain.proceed()
                            // A rejected credential change returns false; exceptions never reach here.
                            if (result != false) runCatching {
                                synchronized(securityRefreshLock) {
                                    securityRefreshHandler.removeCallbacks(securityRefresh)
                                    securityRefreshHandler.removeCallbacks(securityFollowUp)
                                    securityRefreshHandler.postDelayed(securityRefresh, 2_000L)
                                    // Samsung can update DMC after the framework call returns.
                                    securityRefreshHandler.postDelayed(securityFollowUp, 12_000L)
                                }
                                Log.i(TAG, "DMC_SECURITY_SETUP_CHANGED source=$className#${method.name}")
                            }.onFailure { Log.w(TAG, "DMC_SETUP_REFRESH_SCHEDULE_FAILED", it) }
                            result
                        }
                        Log.i(TAG, "DMC_SETUP_OBSERVER_INSTALLED target=$className#${method.name}")
                    }.onFailure { Log.w(TAG, "DMC_SETUP_OBSERVER_FAILED target=$className#${method.name}", it) }
                }
            }.onFailure { Log.w(TAG, "DMC_SETUP_CLASS_UNAVAILABLE target=$className", it) }
        }
    }

    private fun refreshAfterSecuritySetup() {
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Context
                ?: error("system_server application unavailable")
            // Recover even if D2's app is stopped. The app signal also refreshes its cached UI status.
            scheduleDmcRecovery(app, delay = 0L)
            app.sendBroadcast(Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                .setComponent(ComponentName("app.d2lock", "app.d2lock.lockscreen.KeyguardSignalReceiver"))
                .putExtra("event", "SECURITY_SETUP_CHANGED")
                .putExtra("method", "setup_completed")
                .putExtra("source", "system_server_security_setup_observer"))
        }.onFailure { Log.w(TAG, "DMC_SETUP_REFRESH_FAILED", it) }
    }

    private fun installDmcReceiver(app: Context) {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val allowedUid = runCatching { app.packageManager.getPackageUid("app.d2lock", 0) }.getOrNull() ?: return
                if (sentFromUid != allowedUid || allowedUid / 100000 != 0) return
                val reply = intent.getStringExtra("reply") ?: return
                if (!reply.startsWith("app.d2lock.action.DMC_RESULT.")) return
                scheduleDmcRecovery(app, reply, 0L)
            }
        }, android.content.IntentFilter("app.d2lock.action.DMC_REQUEST"), Context.RECEIVER_EXPORTED)
        Log.i(TAG, "DMC_SYSTEM_SERVER_RECEIVER_READY uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
    }

    /** One guarded recovery attempt in system_server; validate before writing and verify read-back. */
    private fun scheduleDmcRecovery(systemContext: Context, reply: String? = null, delay: Long = 10_000L) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching {
                    Thread({
                        var verifiedRecord: ByteArray? = null
                        var detail = "System Server recovery failed"
                        val identity = android.os.Binder.clearCallingIdentity()
                        try {
                            val uid = android.os.Process.myUid()
                            val pid = android.os.Process.myPid()
                            Log.i(TAG, "DMC_SYSTEM_SERVER_RECOVERY_START uid=$uid pid=$pid mode=guarded_recovery")
                            check(uid == 1000) { "Probe requires system_server UID 1000" }
                            synchronized(dmcLock) {
                            val api = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
                            val manager = api.getMethod("getInstance", String::class.java).invoke(null, "DMC")
                                ?: error("VaultKeeper manager unavailable")
                            val data = api.getMethod("read", Int::class.javaPrimitiveType).invoke(manager, 1) as? ByteArray
                            if (data == null) {
                                detail = "System Server DMC read returned no record; access or initialization unverified"
                                Log.w(TAG, "DMC_SYSTEM_SERVER_RECOVERY_NO_RECORD uid=$uid pid=$pid access_or_initialization_unverified")
                            } else {
                                app.d2lock.root.DmcVaultPolicy.validate(data)
                                val readMethod = api.getMethod("read", Int::class.javaPrimitiveType)
                                val writeMethod = api.getMethod("write", Int::class.javaPrimitiveType, ByteArray::class.java)
                                val vault = object : app.d2lock.root.DmcVaultPolicy.Vault {
                                    override fun read(): ByteArray? = readMethod.invoke(manager, 1) as? ByteArray
                                    override fun write(record: ByteArray): Int = writeMethod.invoke(manager, 1, record) as Int
                                }
                                val verified = app.d2lock.root.DmcVaultPolicy.enforce(vault)
                                verifiedRecord = verified
                                detail = "Targets verified inside System Server UID $uid PID $pid"
                                Log.i(TAG, "DMC_SYSTEM_SERVER_RECOVERY_VERIFIED screenLock=${verified[0]} maintenanceMode=${verified[1]} downloadAtCommand=${verified[2]} downloadAllowed=${app.d2lock.root.DmcVaultPolicy.downloadAllowed(verified)}")
                                Log.i(TAG, "DMC_SYSTEM_SERVER_RECOVERY_INITIAL_READ uid=$uid pid=$pid length=${data.size} screenLock=${data[0]} maintenanceMode=${data[1]} downloadAtCommand=${data[2]} downloadAllowed=${app.d2lock.root.DmcVaultPolicy.downloadAllowed(data)} targetsMet=${app.d2lock.root.DmcVaultPolicy.matchesTarget(data)} mode=guarded_recovery")
                            }
                            }
                        } catch (error: Throwable) {
                            val cause = (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                            detail = "${cause.javaClass.simpleName}: ${cause.message}"
                            Log.w(TAG, "DMC_SYSTEM_SERVER_RECOVERY_FAILED mode=guarded_recovery", cause)
                        } finally {
                            android.os.Binder.restoreCallingIdentity(identity)
                            if (reply != null && android.os.Build.VERSION.SDK_INT >= 34) runCatching {
                                val data = verifiedRecord
                                systemContext.sendBroadcast(Intent(reply).setPackage("app.d2lock")
                                    .putExtra("verified", data != null).putExtra("detail", detail)
                                    .putExtra("lock", data?.get(0)?.toInt() ?: -1)
                                    .putExtra("maint", data?.get(1)?.toInt() ?: -1)
                                    .putExtra("at", data?.get(2)?.toInt() ?: -1)
                                    .putExtra("download", if (data != null) 1 else -1), null,
                                    android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
                            }.onFailure { Log.w(TAG, "DMC_SYSTEM_SERVER_RESULT_FAILED", it) }
                        }
                    }, "D2-DMC-Recovery").apply { isDaemon = true }.start()
                }.onFailure { Log.w(TAG, "DMC_SYSTEM_SERVER_RECOVERY_THREAD_FAILED", it) }
            }, delay)
        }.onFailure { Log.w(TAG, "DMC_SYSTEM_SERVER_RECOVERY_SCHEDULE_FAILED", it) }
    }

    private fun signalBootReady() {
        if (!bootReadySent.compareAndSet(false, true)) {
            log(Log.DEBUG, TAG, "GUARDIAN_MINIMAL_BOOT_READY_DEDUPED")
            return
        }

        runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread
                .getDeclaredMethod("currentApplication")
                .invoke(null) as? Context
                ?: error("system_server application unavailable")

            app.sendBroadcast(
                Intent("app.d2lock.action.XPOSED_SYSTEM_EVENT")
                    .setComponent(
                        ComponentName(
                            "app.d2lock",
                            "app.d2lock.lockscreen.KeyguardSignalReceiver"
                        )
                    )
                    .putExtra("event", "BOOT_READY")
                    .putExtra("method", "systemReady")
                    .putExtra(
                        "source",
                        "com.android.server.am.ActivityManagerService#systemReady"
                    )
            )

            log(Log.INFO, TAG, "GUARDIAN_MINIMAL_BOOT_READY_SENT")
            installDmcReceiver(app)
            scheduleDmcRecovery(app)
        }.onFailure {
            // No retry loop or heartbeat. If signalling fails, leave Android alone.
            log(Log.WARN, TAG, "GUARDIAN_MINIMAL_BOOT_READY_SIGNAL_FAILED", it)
        }
    }
}
