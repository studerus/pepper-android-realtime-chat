package io.github.anonymous.pepper_realtime.tools.information

import io.github.anonymous.pepper_realtime.network.HttpClientManager
import io.github.anonymous.pepper_realtime.tools.ApiKeyRequirement
import io.github.anonymous.pepper_realtime.tools.ApiKeyType
import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Tool for getting weather information using OpenWeatherMap API.
 * Provides current weather and 5-day forecast for specified locations.
 */
@Suppress("SpellCheckingInspection") // API parameter names like appid, desc, ddT, etc.
class GetWeatherTool : Tool {

    override fun getName(): String = "get_weather"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Gets the current weather AND the 5-day forecast for a specific location. Always provides both current conditions and future forecast.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location", JSONObject()
                        .put("type", "string")
                        .put("description", "The city or location to get weather information for"))
                })
                put("required", JSONArray().put("location"))
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val location = args.optString("location", "")
        if (location.isEmpty()) {
            return JSONObject()
                .put("success", false)
                .put("error", "Please provide a location for the weather query.")
                .toString()
        }

        if (!context.apiKeyManager.isWeatherAvailable()) {
            return JSONObject()
                .put("success", false)
                .put("error", context.apiKeyManager.weatherSetupMessage)
                .toString()
        }

        val apiKey = context.apiKeyManager.openWeatherApiKey

        // Use optimized shared client for better performance and connection reuse
        val client = HttpClientManager.getInstance().getQuickApiClient()

        val locationQueryParam: String
        val locationInputForName: String
        val lower = location.lowercase()
        if (lower.contains("baden")) {
            locationQueryParam = "lat=47.47&lon=8.30" // Baden, CH approx
            locationInputForName = "Baden, CH"
        } else {
            locationQueryParam = "q=" + URLEncoder.encode(location, "UTF-8")
            locationInputForName = location
        }

        val units = "metric"
        val lang = "en"
        val actualFetchTime = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.US).format(Date())

        var currentData: JSONObject? = null
        var forecastData: JSONObject? = null
        var currentError: String? = null
        var forecastError: String? = null

        // Fetch current weather
        try {
            val currentUrl = "https://api.openweathermap.org/data/2.5/weather?$locationQueryParam&appid=$apiKey&units=$units&lang=$lang"
            val currentReq = Request.Builder().url(currentUrl).build()
            client.newCall(currentReq).execute().use { r ->
                val responseBody = r.body
                if (responseBody == null) {
                    currentError = "Failed to fetch current weather: Empty response body"
                } else {
                    val body = responseBody.string()
                    val data = JSONObject(body)
                    if (r.isSuccessful && (data.optInt("cod", 200) == 200 || data.optString("cod") == "200")) {
                        currentData = data
                    } else {
                        currentError = "Failed to fetch current weather: ${data.optString("message", "API error")}"
                    }
                }
            }
        } catch (e: Exception) {
            currentError = "Network error fetching current weather."
        }

        // Fetch forecast
        try {
            val forecastUrl = "https://api.openweathermap.org/data/2.5/forecast?$locationQueryParam&appid=$apiKey&units=$units&lang=$lang"
            val forecastReq = Request.Builder().url(forecastUrl).build()
            client.newCall(forecastReq).execute().use { r ->
                val responseBody = r.body
                if (responseBody == null) {
                    forecastError = "Failed to fetch forecast: Empty response body"
                } else {
                    val body = responseBody.string()
                    val data = JSONObject(body)
                    if (r.isSuccessful && (data.optInt("cod", 200) == 200 || data.optString("cod") == "200")) {
                        forecastData = data
                    } else {
                        forecastError = "Failed to fetch forecast: ${data.optString("message", "API error")}"
                    }
                }
            }
        } catch (e: Exception) {
            forecastError = "Network error fetching forecast."
        }

        if (currentData == null && forecastData == null) {
            return JSONObject()
                .put("success", false)
                .put("error", "Failed to fetch weather data. Current Error: $currentError. Forecast Error: $forecastError")
                .toString()
        }

        // Build weather summary
        val locationName = if (forecastData != null) {
            forecastData!!.optJSONObject("city")?.optString("name", locationInputForName) ?: locationInputForName
        } else {
            currentData!!.optString("name", locationInputForName)
        }

        val timezoneOffset = if (forecastData != null) {
            forecastData!!.optJSONObject("city")?.optInt("timezone", 0) ?: 0
        } else {
            currentData!!.optInt("timezone", 0)
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val summary = StringBuilder()
        summary.append("Weather Report for $locationName (Retrieved: $actualFetchTime):\n\n")

        // Current weather
        if (currentData != null) {
            val data = currentData!!
            val main = data.optJSONObject("main")
            val weatherArr = data.optJSONArray("weather")
            val wind = data.optJSONObject("wind")
            val temp = main?.optDouble("temp")?.roundToInt() ?: 0
            val feels = main?.optDouble("feels_like")?.roundToInt() ?: 0
            val desc = weatherArr?.optJSONObject(0)?.optString("description", "N/A") ?: "N/A"
            val humidity = main?.optInt("humidity", 0) ?: 0
            val windSpeed = wind?.optDouble("speed", 0.0) ?: 0.0
            summary.append("**Current (${getReadableTime(data.optLong("dt"), timezoneOffset)}):** ")
                .append("${temp}°C (feels like ${feels}°C), $desc, ${humidity}% humidity, Wind $windSpeed m/s.\n\n")
        } else {
            summary.append("**Current:** No data available ($currentError).\n\n")
        }

        // Forecast summary (simplified for space)
        if (forecastData != null && forecastData!!.optJSONArray("list") != null) {
            summary.append("**Forecast:**\n")
            summary.append("(Today is ${getReadableDate(nowSec, timezoneOffset)}. The following are predictions.)\n\n")

            val byDay = linkedMapOf<String, MutableList<JSONObject>>()
            val list = forecastData!!.optJSONArray("list")
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val dateStr = getReadableDate(item.optLong("dt"), timezoneOffset)
                    byDay.getOrPut(dateStr) { mutableListOf() }.add(item)
                }
            }

            val days = byDay.keys.take(5)
            for (day in days) {
                val items = byDay[day] ?: continue
                var min = Double.POSITIVE_INFINITY
                var max = Double.NEGATIVE_INFINITY
                val descs = mutableListOf<String>()

                for (item in items) {
                    val m = item.optJSONObject("main")
                    if (m != null) {
                        val t = m.optDouble("temp", Double.NaN)
                        if (!t.isNaN()) {
                            min = minOf(min, t)
                            max = maxOf(max, t)
                        }
                    }
                    val wArr = item.optJSONArray("weather")
                    if (wArr != null && wArr.length() > 0) {
                        val d = wArr.optJSONObject(0)?.optString("description", "") ?: ""
                        if (d.isNotEmpty()) descs.add(d)
                    }
                }

                val minT = if (min == Double.POSITIVE_INFINITY) null else min.roundToInt()
                val maxT = if (max == Double.NEGATIVE_INFINITY) null else max.roundToInt()
                val unique = descs.toSet()
                val cond = if (unique.isEmpty()) "No specific conditions available" else unique.joinToString(", ")

                summary.append("**$day:**\n")
                summary.append("  Overall: Min ${minT ?: "N/A"}°C, Max ${maxT ?: "N/A"}°C. Conditions: $cond.\n\n")
            }
        } else {
            summary.append("**Forecast:** No data available ($forecastError).\n")
        }

        return JSONObject()
            .put("success", true)
            .put("summary", summary.toString().trim())
            .toString()
    }

    override val apiKeyRequirement = ApiKeyRequirement.Required(ApiKeyType.OPENWEATHER)

    private fun getReadableTime(epochSeconds: Long, tzOffsetSeconds: Int): String {
        val localMillis = (epochSeconds + tzOffsetSeconds) * 1000L
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(localMillis))
    }

    private fun getReadableDate(epochSeconds: Long, tzOffsetSeconds: Int): String {
        val localMillis = (epochSeconds + tzOffsetSeconds) * 1000L
        val sdf = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(localMillis))
    }
}


