package app.d2lock.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.ProviderCreateCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest
import android.os.OutcomeReceiver

/**
 * Stage-one D2 Credential Manager provider.
 *
 * Registration is intentionally functional but returns no passkey candidates yet.
 * Storage, D2 PIN/pattern authorization and WebAuthn signing are added only after
 * this provider skeleton is proven by CI and Android's provider discovery UI.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class D2CredentialProviderService : CredentialProviderService() {
    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>
    ) {
        callback.onResult(BeginGetCredentialResponse.Builder().build())
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onResult(BeginCreateCredentialResponse())
            return
        }
        val intent = Intent(this, D2PasskeyActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0xD200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val entry = CreateEntry.Builder("Samsung Lock D2", pendingIntent)
            .setDescription("Save this passkey with D2")
            .setPublicKeyCredentialCount(runCatching { D2PasskeyStore(this).list().size }.getOrDefault(0))
            .build()
        callback.onResult(
            BeginCreateCredentialResponse.Builder()
                .addCreateEntry(entry)
                .build()
        )
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}
