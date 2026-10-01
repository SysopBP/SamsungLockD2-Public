package app.d2lock.security

import android.app.admin.DeviceAdminReceiver

/**
 * Optional non-root screen-off fallback for the D2 lock surface.
 * Android requires the user to explicitly enable this receiver as a device admin.
 */
class D2DeviceAdminReceiver : DeviceAdminReceiver()
