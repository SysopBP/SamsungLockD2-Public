package app.d2lock.weather

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import app.d2lock.Prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

data class Weather(val temperature: Int, val label: String)

object WeatherRepository {
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("MissingPermission")
    fun load(context: Context, result: (Weather?) -> Unit) {
        val manual = Prefs.weatherLocation(context).trim()
        if (manual.isNotEmpty()) {
            executor.execute {
                result(runCatching {
                    val geo = geocode(manual)
                    loadOpenMeteo(context, geo.first, geo.second)
                }.getOrNull())
            }
            return
        }

        val location = runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull() ?: return result(null)

        executor.execute {
            result(runCatching { loadOpenMeteo(context, location.latitude, location.longitude) }.getOrNull())
        }
    }

    private fun geocode(query: String): Pair<Double, Double> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json")
        val results = json.optJSONArray("results") ?: error("Location not found")
        if (results.length() == 0) error("Location not found")
        val place = results.getJSONObject(0)
        return place.getDouble("latitude") to place.getDouble("longitude")
    }

    private fun loadOpenMeteo(context: Context, latitude: Double, longitude: Double): Weather {
        val json = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,weather_code&temperature_unit=celsius"
        )
        val current = json.getJSONObject("current")
        val c = current.getDouble("temperature_2m")
        val temperature = if (Prefs.celsius(context)) c else (c * 9.0 / 5.0) + 32.0
        return Weather(
            kotlin.math.round(temperature).toInt(),
            codeLabel(current.optInt("weather_code", -1))
        )
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("User-Agent", "SamsungLockD2/0.5 (github.com/SysopBP/SamsungLockD2-Public)")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (connection.responseCode !in 200..299) error("Weather HTTP ${connection.responseCode}")
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }

    private fun codeLabel(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Showers"
        85, 86 -> "Snow showers"
        in 95..99 -> "Storms"
        else -> "Weather"
    }
}
