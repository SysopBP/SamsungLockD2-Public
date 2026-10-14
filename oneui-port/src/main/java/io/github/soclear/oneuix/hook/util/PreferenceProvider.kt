/* OneUIX adaptation for D2, 2026-10-14. SPDX-License-Identifier: AGPL-3.0-only
 * Upstream: SoClear/OneUIX @ 591d09c9cc69faf3c8faf258e39869b2df7981f4. */
package io.github.soclear.oneuix.hook.util

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedModule
import io.github.soclear.oneuix.common.IgnoreUnknownKeysJson
import io.github.soclear.oneuix.common.Preference
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream

object PreferenceProvider {
    // libxposed 102 通过框架的 remote file 机制读取模块配置
    // （宿主进程对 remote file 只读，由模块 App 通过 libxposed service 推送到共享目录）
    @OptIn(ExperimentalSerializationApi::class)
    context(xposedModule: XposedModule)
    fun loadPreference(): Preference? { return try {
        val parcelFileDescriptor = xposedModule.openRemoteFile(Preference.FILE_NAME)
        if (parcelFileDescriptor.statSize <= 0L) {
            parcelFileDescriptor.close()
            return null
        }
        ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).use { inputStream ->
            val text = inputStream.readBytes().toString(Charsets.UTF_8)
            if (!org.json.JSONObject(text).optBoolean("d2PortEnabled", false)) null
            else IgnoreUnknownKeysJson.decodeFromString<Preference>(text)
        }
    } catch (_: Exception) {
        null
    } catch (t: Throwable) {
        xlog(t)
        null
    }
    }
}
