package app.d2lock.bridge

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import app.d2lock.security.PinStore

/** Grants the paired Galaxy Island build an immutable lock-only launch capability. */
class IslandProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        ctx.enforceCallingPermission("app.d2lock.permission.ISLAND", "Paired build required")
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        check(callers.contains("app.cutout.ringpreview") &&
            ctx.packageManager.checkSignatures("app.cutout.ringpreview", ctx.packageName) == PackageManager.SIGNATURE_MATCH) {
            "Galaxy Island caller required"
        }
        require(method == "state") { "Unsupported bridge operation" }
        val ready = IslandBridge.enabled(ctx) && PinStore(ctx).configured()
        return Bundle().apply {
            putInt("protocol", 1)
            putBoolean("locked", IslandBridge.locked(ctx))
            putBoolean("ready", ready)
            if (ready) {
                putParcelable("lock", PendingIntent.getActivity(ctx, 710,
                    Intent(ctx, IslandLockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
