package app.d2lock.passkeys

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.provider.PendingIntentHandler

/**
 * Selection target for Credential Manager create/get entries.
 *
 * Stage 3 deliberately authenticates with D2 before touching passkey key material.
 * The final WebAuthn response is added in the following stage.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class D2PasskeyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val create = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        val get = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent)
        val selectedId = intent.getStringExtra("d2_passkey_id")
        if (create == null && get != null && selectedId != null) {
            val option = get.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().firstOrNull()
            if (option == null) {
                setResult(RESULT_CANCELED); finish(); return
            }
            D2PasskeyAuth.authorize(
                this,
                success = {
                    runCatching {
                        val store = D2PasskeyStore(this)
                        val record = store.list().first { it.id == selectedId }
                        val json = D2WebAuthn.authenticationResponse(option.requestJson, record, store)
                        val response = GetCredentialResponse(PublicKeyCredential(json))
                        PendingIntentHandler.setGetCredentialResponse(intent, response)
                        setResult(RESULT_OK, intent)
                    }.onFailure { setResult(RESULT_CANCELED) }
                    finish()
                },
                cancel = { setResult(RESULT_CANCELED); finish() }
            )
            return
        }
        if (create == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val request = create.callingRequest
        if (request !is CreatePublicKeyCredentialRequest) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        D2PasskeyAuth.authorize(
            this,
            success = {
                runCatching {
                    val options = D2WebAuthn.parseCreateRequest(request.requestJson)
                    val store = D2PasskeyStore(this)
                    val record = store.create(
                        options.rpId,
                        options.userId,
                        options.userName,
                        options.displayName
                    )
                    try {
                        val responseJson = D2WebAuthn.registrationResponse(
                            request.requestJson,
                            record,
                            store.ecPublicKey(record)
                        )
                        val response = CreatePublicKeyCredentialResponse(responseJson)
                        PendingIntentHandler.setCreateCredentialResponse(intent, response)
                        setResult(RESULT_OK, intent)
                    } catch (error: Exception) {
                        store.delete(record.id)
                        throw error
                    }
                }.onFailure {
                    setResult(RESULT_CANCELED)
                }
                finish()
            },
            cancel = {
                setResult(RESULT_CANCELED)
                finish()
            }
        )
    }
}
