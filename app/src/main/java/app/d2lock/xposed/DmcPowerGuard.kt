package app.d2lock.xposed

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import app.d2lock.root.DmcVaultPolicy
import app.d2lock.root.DmcPowerDecision
import java.util.concurrent.atomic.AtomicBoolean

/** Runs only inside system_server. No cached status can authorize a power action. */
internal object DmcPowerGuard {
    private const val TAG = "D2DmcPowerGuard"
    private const val TIMEOUT_MS = 5_000L
    private val busy = AtomicBoolean(false)
    private val bypass = ThreadLocal<Boolean>()
    @Volatile private var activeDecision: DmcPowerDecision? = null
    fun cancelPending() { activeDecision?.cancel() }
    fun isEmergency(reason: String?) = reason?.startsWith("thermal") == true || reason == "battery"
    // Shared with boot/setup recovery so vault read-modify-write operations cannot overlap.
    val vaultLock = Any()
    fun isBusy() = busy.get()
    fun isResuming() = bypass.get() == true

    fun verifyVault(): ByteArray = synchronized(vaultLock) {
        check(Process.myUid() == 1000) { "D2 SRP verification requires System Server UID 1000" }
        val api = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
        val manager = api.getMethod("getInstance", String::class.java).invoke(null, "DMC")
            ?: error("D2 SRP VaultKeeper unavailable")
        val read = api.getMethod("read", Int::class.javaPrimitiveType)
        val write = api.getMethod("write", Int::class.javaPrimitiveType, ByteArray::class.java)
        val vault = object : DmcVaultPolicy.Vault {
            override fun read() = read.invoke(manager, 1) as? ByteArray
            override fun write(record: ByteArray) = write.invoke(manager, 1, record) as Int
        }
        val verified = DmcVaultPolicy.enforce(vault)
        // Even an already-correct record gets a second read immediately before approval.
        val finalRead = vault.read()
        DmcVaultPolicy.validate(finalRead)
        check(verified.contentEquals(finalRead) && DmcVaultPolicy.matchesTarget(finalRead)) {
            "D2 SRP changed during power-action verification"
        }
        finalRead!!
    }

    fun intercept(context: Context, action: String, resume: () -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            Log.w(TAG, "D2_SRP_POWER_DUPLICATE action=$action")
            return
        }
        val main = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        val decision = DmcPowerDecision(deadline)
        activeDecision = decision
        fun message(text: String) { runCatching { Toast.makeText(context, text, Toast.LENGTH_LONG).show() } }
        main.post { message("D2 SRP: checking before $action…") }
        val timeout = Runnable {
            if (decision.cancel()) {
                Log.e(TAG, "D2_SRP_POWER_CANCELLED action=$action reason=timeout")
                message("D2 cancelled $action: D2 SRP verification timed out. Check SRP status before retrying.")
                // Keep busy while a stuck vault call is still running; never queue more workers.
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        try {
            Thread({
                val identity = Binder.clearCallingIdentity()
                val result = try { runCatching { verifyVault() } }
                    finally { Binder.restoreCallingIdentity(identity) }
                main.post {
                    try {
                        if (SystemClock.elapsedRealtime() >= deadline) timeout.run()
                        if (!decision.complete(SystemClock.elapsedRealtime())) return@post
                        main.removeCallbacks(timeout)
                        result.fold(onSuccess = {
                            Log.i(TAG, "D2_SRP_POWER_VERIFIED action=$action uid=${Process.myUid()} pid=${Process.myPid()} lock=${it[0]} maintenance=${it[1]} at=${it[2]}")
                            bypass.set(true)
                            try { resume() } finally { bypass.remove() }
                        }, onFailure = {
                            Log.e(TAG, "D2_SRP_POWER_CANCELLED action=$action reason=verification_failed", it)
                            message("D2 cancelled $action: Samsung Recovery Protection could not be verified. Open D2 SRP status and retry.")
                        })
                    } catch (error: Throwable) {
                        Log.e(TAG, "D2_SRP_POWER_RESUME_FAILED action=$action", error)
                        message("D2 could not continue $action. Please retry from the power menu.")
                    } finally { activeDecision = null; busy.set(false) }
                }
            }, "D2-SRP-PowerGuard").apply { isDaemon = true }.start()
        } catch (error: Throwable) {
            main.removeCallbacks(timeout)
            decision.cancel()
            activeDecision = null
            busy.set(false)
            Log.e(TAG, "D2_SRP_POWER_CANCELLED action=$action reason=worker_failed", error)
            main.post { message("D2 cancelled $action: verification could not start.") }
        }
    }
}
