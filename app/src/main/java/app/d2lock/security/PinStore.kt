package app.d2lock.security

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Independent app credential. Never reads or writes Android lock settings. */
class PinStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "d2-pin.json"))
    companion object { private val guard = Any(); private const val ITERATIONS = 210_000 }
    fun configured(): Boolean = synchronized(guard) { file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() }
    private fun read() = JSONObject(file.openRead().bufferedReader().use { it.readText() })
    private fun write(value: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun hash(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun create(pin: CharArray) = synchronized(guard) {
        check(!configured()) { "PIN already configured" }
        save(pin)
    }
    private fun save(pin: CharArray) {
        require(pin.size == 6 && pin.all { it in '0'..'9' })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        write(JSONObject().put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .put("failures", 0).put("until", 0L))
    }
    /** A wrong PIN or failed storage read never unlocks. Delays survive process restarts. */
    fun verify(pin: CharArray): Boolean = synchronized(guard) {
        val state = read()
        if (remaining(state) > 0) return@synchronized false
        val valid = pin.size == 6 && pin.all { it in '0'..'9' } && MessageDigest.isEqual(
            Base64.decode(state.getString("hash"), Base64.NO_WRAP),
            hash(pin, Base64.decode(state.getString("salt"), Base64.NO_WRAP)))
        if (valid) { state.put("failures", 0).put("until", 0L) }
        else {
            val failures = (state.optInt("failures") + 1).coerceAtMost(100)
            state.put("failures", failures)
            if (failures >= 5) state.put("until", now() + (30_000L shl ((failures - 5) / 5).coerceAtMost(5)))
        }
        write(state)
        valid
    }
    fun change(oldPin: CharArray, newPin: CharArray): Boolean = synchronized(guard) {
        if (!verify(oldPin)) false else { save(newPin); true }
    }
    private fun remaining(state: JSONObject) = (state.optLong("until") - now()).coerceAtLeast(0L)
    fun remainingMillis(): Long = synchronized(guard) { remaining(read()) }
}
