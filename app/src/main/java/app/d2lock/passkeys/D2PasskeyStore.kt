package app.d2lock.passkeys

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Private storage foundation for D2 passkeys.
 *
 * Metadata is encrypted at rest with an AES-256 key held by Android Keystore.
 * Per-passkey signing keys are non-exportable P-256 keys held by Android Keystore.
 * No D2 PIN or pattern material is stored here.
 */
class D2PasskeyStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "d2-passkeys.bin"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val MASTER_ALIAS = "d2_passkey_metadata_v1"
        private const val SIGNING_PREFIX = "d2_passkey_sign_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val guard = Any()
    }

    data class Record(
        val id: String,
        val rpId: String,
        val userId: String,
        val userName: String,
        val displayName: String,
        val keyAlias: String,
        val createdAt: Long
    )

    private fun masterKey(): SecretKey {
        (keyStore.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun readJson(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject().put("version", 1).put("records", JSONArray())
        val packed = file.openRead().use { it.readBytes() }
        require(packed.size > 13) { "Invalid D2 passkey store" }
        val ivSize = packed[0].toInt() and 0xff
        require(ivSize in 12..16 && packed.size > 1 + ivSize) { "Invalid D2 passkey store IV" }
        val iv = packed.copyOfRange(1, 1 + ivSize)
        val ciphertext = packed.copyOfRange(1 + ivSize, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
        return JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
    }

    private fun writeJson(json: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val ciphertext = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteArray(1 + iv.size + ciphertext.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)
        val out = file.startWrite()
        try {
            out.write(packed)
            file.finishWrite(out)
        } catch (error: Exception) {
            file.failWrite(out)
            throw error
        } finally {
            packed.fill(0)
            ciphertext.fill(0)
        }
    }

    fun list(): List<Record> = synchronized(guard) {
        val array = readJson().optJSONArray("records") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(
                    Record(
                        item.getString("id"),
                        item.getString("rpId"),
                        item.getString("userId"),
                        item.optString("userName"),
                        item.optString("displayName"),
                        item.getString("keyAlias"),
                        item.optLong("createdAt")
                    )
                )
            }
        }
    }

    fun create(
        rpId: String,
        userId: String,
        userName: String,
        displayName: String
    ): Record = synchronized(guard) {
        require(rpId.isNotBlank() && userId.isNotBlank())
        val idBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val id = Base64.encodeToString(idBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        idBytes.fill(0)
        val alias = SIGNING_PREFIX + id
        val generator = KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore"
        )
        generator.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_SIGN
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .build()
        )
        generator.generateKeyPair()

        val record = Record(id, rpId, userId, userName, displayName, alias, System.currentTimeMillis())
        val json = readJson()
        val array = json.optJSONArray("records") ?: JSONArray().also { json.put("records", it) }
        array.put(JSONObject()
            .put("id", record.id)
            .put("rpId", record.rpId)
            .put("userId", record.userId)
            .put("userName", record.userName)
            .put("displayName", record.displayName)
            .put("keyAlias", record.keyAlias)
            .put("createdAt", record.createdAt))
        try {
            writeJson(json)
            record
        } catch (error: Exception) {
            keyStore.deleteEntry(alias)
            throw error
        }
    }

    fun publicKey(record: Record): ByteArray =
        requireNotNull(keyStore.getCertificate(record.keyAlias)) { "Missing D2 passkey key" }.publicKey.encoded

    fun ecPublicKey(record: Record): java.security.interfaces.ECPublicKey =
        requireNotNull(keyStore.getCertificate(record.keyAlias)) { "Missing D2 passkey key" }
            .publicKey as java.security.interfaces.ECPublicKey

    fun sign(record: Record, payload: ByteArray): ByteArray {
        val privateKey = requireNotNull(keyStore.getKey(record.keyAlias, null)) { "Missing D2 passkey key" }
        return java.security.Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey as java.security.PrivateKey)
            update(payload)
            sign()
        }
    }

    fun delete(id: String): Boolean = synchronized(guard) {
        val json = readJson()
        val source = json.optJSONArray("records") ?: return@synchronized false
        val replacement = JSONArray()
        var alias: String? = null
        for (i in 0 until source.length()) {
            val item = source.getJSONObject(i)
            if (item.optString("id") == id) alias = item.optString("keyAlias")
            else replacement.put(item)
        }
        if (alias == null) return@synchronized false
        json.put("records", replacement)
        writeJson(json)
        runCatching { keyStore.deleteEntry(alias) }
        true
    }
}
