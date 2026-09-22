package app.d2lock.passkeys

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey

/** Minimal WebAuthn registration encoder for ES256 resident credentials. */
object D2WebAuthn {
    data class CreateOptions(
        val rpId: String,
        val userId: String,
        val userName: String,
        val displayName: String,
        val challenge: String,
        val origin: String?
    )

    fun parseCreateRequest(json: String): CreateOptions {
        val root = JSONObject(json)
        val rp = root.getJSONObject("rp")
        val user = root.getJSONObject("user")
        val rpId = rp.getString("id")
        val userId = user.getString("id")
        require(rpId.isNotBlank() && userId.isNotBlank())
        return CreateOptions(
            rpId = rpId,
            userId = userId,
            userName = user.optString("name"),
            displayName = user.optString("displayName"),
            challenge = root.getString("challenge"),
            origin = null
        )
    }

    fun registrationResponse(
        requestJson: String,
        record: D2PasskeyStore.Record,
        publicKey: ECPublicKey
    ): String {
        val options = parseCreateRequest(requestJson)
        val clientData = JSONObject()
            .put("type", "webauthn.create")
            .put("challenge", options.challenge)
            .put("origin", "android:apk-key-hash:SamsungLockD2")
            .toString().toByteArray(Charsets.UTF_8)

        val rpHash = MessageDigest.getInstance("SHA-256")
            .digest(options.rpId.toByteArray(Charsets.UTF_8))
        val credentialId = b64d(record.id)
        val coseKey = coseEc2(publicKey)
        val authData = ByteArrayOutputStream().apply {
            write(rpHash)
            write(byteArrayOf(0x45)) // UP + UV + AT: D2 authentication occurred before creation.
            write(byteArrayOf(0, 0, 0, 0))
            write(ByteArray(16)) // zero AAGUID: D2 does not claim external authenticator certification.
            write(byteArrayOf(((credentialId.size ushr 8) and 0xff).toByte(), (credentialId.size and 0xff).toByte()))
            write(credentialId)
            write(coseKey)
        }.toByteArray()
        val attestation = cborMap(
            "fmt" to cborText("none"),
            "attStmt" to byteArrayOf(0xA0.toByte()),
            "authData" to cborBytes(authData)
        )
        return JSONObject()
            .put("id", record.id)
            .put("rawId", record.id)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", JSONObject()
                .put("clientDataJSON", b64(clientData))
                .put("attestationObject", b64(attestation))
                .put("transports", org.json.JSONArray().put("internal")))
            .toString()
    }

    private fun coseEc2(key: ECPublicKey): ByteArray {
        fun coord(v: java.math.BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return cborIntMap(
            1 to cborInt(2),       // kty EC2
            3 to cborInt(-7),      // alg ES256
            -1 to cborInt(1),      // crv P-256
            -2 to cborBytes(coord(key.w.affineX)),
            -3 to cborBytes(coord(key.w.affineY))
        )
    }

    private fun b64(v: ByteArray)=Base64.encodeToString(v,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun b64d(v:String)=Base64.decode(v,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun cborBytes(v:ByteArray)=cborHead(2,v.size.toLong())+v
    private fun cborText(v:String):ByteArray { val b=v.toByteArray(); return cborHead(3,b.size.toLong())+b }
    private fun cborInt(v:Int):ByteArray = if(v>=0)cborHead(0,v.toLong()) else cborHead(1,(-1L-v))
    private fun cborMap(vararg values:Pair<String,ByteArray>):ByteArray {
        val o=ByteArrayOutputStream(); o.write(cborHead(5,values.size.toLong()))
        values.forEach { o.write(cborText(it.first)); o.write(it.second) }; return o.toByteArray()
    }
    private fun cborIntMap(vararg values:Pair<Int,ByteArray>):ByteArray {
        val o=ByteArrayOutputStream(); o.write(cborHead(5,values.size.toLong()))
        values.forEach { o.write(cborInt(it.first)); o.write(it.second) }; return o.toByteArray()
    }
    private fun cborHead(major:Int,value:Long):ByteArray = when {
        value < 24 -> byteArrayOf(((major shl 5) or value.toInt()).toByte())
        value <= 0xff -> byteArrayOf(((major shl 5) or 24).toByte(),value.toByte())
        value <= 0xffff -> byteArrayOf(((major shl 5) or 25).toByte(),(value ushr 8).toByte(),value.toByte())
        else -> byteArrayOf(((major shl 5) or 26).toByte(),(value ushr 24).toByte(),(value ushr 16).toByte(),(value ushr 8).toByte(),value.toByte())
    }
}
