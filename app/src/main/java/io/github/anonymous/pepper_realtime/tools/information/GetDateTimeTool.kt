package io.github.anonymous.pepper_realtime.tools.information

import io.github.anonymous.pepper_realtime.tools.Tool
import io.github.anonymous.pepper_realtime.tools.ToolContext
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Tool for getting current date and time with various formatting options.
 * Supports custom formatting, timezones, and locale-specific formatting.
 */
class GetDateTimeTool : Tool {

    override fun getName(): String = "get_current_datetime"

    override fun getDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", getName())
            put("description", "Get the current date and time. Supports optional format and timezone.")

            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("format", JSONObject()
                        .put("type", "string")
                        .put("description", "Formatting style. Default is iso."))
                    put("timezone", JSONObject()
                        .put("type", "string")
                        .put("description", "IANA timezone, e.g. Europe/Zurich."))
                    put("pattern", JSONObject()
                        .put("type", "string")
                        .put("description", "Java SimpleDateFormat pattern."))
                })
            })
        }
    }

    override fun execute(args: JSONObject, context: ToolContext): String {
        val format = args.optString("format", "iso")
        val timezone = args.optString("timezone", "")
        val pattern = args.optString("pattern", "")

        val tz = if (timezone.isEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(timezone)
        val epochMillis = System.currentTimeMillis()
        val now = Date(epochMillis)

        val formatted = when {
            format.equals("locale", ignoreCase = true) -> {
                val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
                df.timeZone = tz
                df.format(now)
            }
            format.equals("custom", ignoreCase = true) && pattern.isNotEmpty() -> {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                sdf.timeZone = tz
                sdf.format(now)
            }
            else -> formatAsRfc3339(now, tz)
        }

        return JSONObject().apply {
            put("datetime", formatted)
            put("epochMillis", epochMillis)
            put("timezone", tz.id)
        }.toString()
    }


    /**
     * Format date as RFC3339 string
     */
    @Suppress("SpellCheckingInspection") // yyyy-MM-dd'T'HH:mm:ssZ is correct RFC3339 format
    private fun formatAsRfc3339(date: Date, timeZone: TimeZone): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        sdf.timeZone = timeZone
        val raw = sdf.format(date)
        return if (raw.length > 5) {
            raw.substring(0, raw.length - 2) + ":" + raw.substring(raw.length - 2)
        } else {
            raw
        }
    }
}


