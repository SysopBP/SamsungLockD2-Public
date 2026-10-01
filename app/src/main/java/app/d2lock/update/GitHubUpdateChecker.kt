package app.d2lock.update

import android.content.Context
import app.d2lock.Prefs
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class D2Release(
    val tag: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val apkUrl: String,
    val prerelease: Boolean,
)

object GitHubUpdateChecker {
    private const val API = "https://api.github.com/repos/SysopBP/SamsungLockD2-Public/releases"

    fun check(context: Context): Result<D2Release?> = runCatching {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kiosk-D2-Boot-Guardian")
        }
        val raw = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        Prefs.setLastUpdateCheck(context, System.currentTimeMillis())
        val releases = JSONArray(raw)
        val channel = Prefs.updateChannel(context)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft", false)) continue
            val prerelease = release.optBoolean("prerelease", false)
            if (channel == "stable" && prerelease) continue
            val tag = release.optString("tag_name")
            if (tag.isBlank() || tag == Prefs.skippedUpdateTag(context)) continue
            val assets = release.optJSONArray("assets") ?: JSONArray()
            var apk = ""
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apk = asset.optString("browser_download_url")
                    break
                }
            }
            if (apk.isBlank()) continue
            val normalizedInstalled = installed.removePrefix("v")
            val normalizedTag = tag.removePrefix("v")
            if (normalizedTag == normalizedInstalled) return@runCatching null
            return@runCatching D2Release(
                tag = tag,
                name = release.optString("name").ifBlank { tag },
                body = release.optString("body"),
                publishedAt = release.optString("published_at"),
                apkUrl = apk,
                prerelease = prerelease,
            )
        }
        null
    }
}
