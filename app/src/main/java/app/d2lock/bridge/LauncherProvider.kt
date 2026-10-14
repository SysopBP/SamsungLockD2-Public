package app.d2lock.bridge

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import app.d2lock.Prefs
import app.d2lock.notifications.CallNotificationStore
import app.d2lock.lockscreen.GuardianWatchdog
import app.d2lock.security.PinStore

/** The current Home app receives immutable lock-only capabilities, never unlock access. */
class LauncherProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        val callers = ctx.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        val home = ctx.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        check(home != null && home in callers) { "Default Home app required" }
        require(method == "state") { "Unsupported operation" }
        val ready = Prefs.enabled(ctx) && PinStore(ctx).configured()
        val blocked = CallNotificationStore.isCallActive(ctx) || GuardianWatchdog.isTrustedAuthenticationActive()
        return Bundle().apply {
            putInt("protocol", 1)
            putBoolean("ready", ready && !blocked)
            putBoolean("locked", ready && IslandBridge.locked(ctx))
            if (ready && !blocked) {
                val options = ActivityOptions.makeBasic().apply {
                    if (Build.VERSION.SDK_INT >= 35) {
                        setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    }
                }.toBundle()
                for (force in listOf(false, true)) {
                    val intent = Intent(ctx, LauncherLockActivity::class.java)
                        .putExtra("force_lock", force)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    putParcelable(if (force) "lock" else "resume", PendingIntent.getActivity(
                        ctx, if (force) 720 else 721, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, options
                    ))
                }
            }
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
