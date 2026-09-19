package app.d2lock.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import app.d2lock.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Weather(val temperature: Int, val label: String)

object WeatherRepository {
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    fun load(context: Context, result: (Weather?) -> Unit) {
        val location = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull() ?: return result(null)
        executor.execute {
            val weather = runCatching {
                val unit = if (Prefs.celsius(context)) "celsius" else "fahrenheit"
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}&current=temperature_2m,weather_code&temperature_unit=$unit")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000; readTimeout = 5000
                    setRequestProperty("User-Agent", "SamsungLockD2/0.2")
                }
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                Weather(json.getDouble("temperature_2m").toInt(), codeLabel(json.getInt("weather_code")))
            }.getOrNull()
            result(weather)
        }
    }

    private fun codeLabel(code: Int) = when (code) {
        0 -> "Clear"
        1, 2, 3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        in 95..99 -> "Storms"
        else -> "Weather"
    }
}
