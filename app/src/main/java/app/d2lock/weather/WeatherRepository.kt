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
                val points = getJson("https://api.weather.gov/points/${location.latitude},${location.longitude}")
                val stationsUrl = points.getJSONObject("properties").getString("observationStations")
                val stations = getJson(stationsUrl).getJSONArray("features")
                if (stations.length() == 0) error("No NWS observation station")
                val stationId = stations.getJSONObject(0).getJSONObject("properties").getString("stationIdentifier")
                val obs = getJson("https://api.weather.gov/stations/$stationId/observations/latest").getJSONObject("properties")
                val c = obs.getJSONObject("temperature").optDouble("value", Double.NaN)
                if (c.isNaN()) error("NWS temperature unavailable")
                val temperature = if (Prefs.celsius(context)) c else (c * 9.0 / 5.0) + 32.0
                Weather(kotlin.math.round(temperature).toInt(), obs.optString("textDescription", "Weather").trim().ifBlank { "Weather" })
            }.getOrNull()
            result(weather)
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "SamsungLockD2/0.2 (github.com/SysopBP/SamsungLockD2-Public)")
            setRequestProperty("Accept", "application/geo+json")
        }
        return try {
            if (connection.responseCode !in 200..299) error("NWS HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
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
