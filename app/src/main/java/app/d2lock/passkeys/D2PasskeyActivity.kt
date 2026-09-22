package app.d2lock.passkeys

import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
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
                // Do not create a credential until the WebAuthn response can be
                // completed atomically and returned to Credential Manager.
                setResult(RESULT_CANCELED)
                finish()
            },
            cancel = {
                setResult(RESULT_CANCELED)
                finish()
            }
        )
    }
}
