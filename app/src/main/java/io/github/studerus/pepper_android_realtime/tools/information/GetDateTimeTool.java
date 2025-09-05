package io.github.studerus.pepper_android_realtime.tools.information;

import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tool for getting current date and time with various formatting options.
 * Supports custom formatting, timezones, and locale-specific formatting.
 */
public class GetDateTimeTool implements Tool {

    @Override
    public String getName() {
        return "get_current_datetime";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Get the current date and time. Supports optional format and timezone.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("format", new JSONObject()
                .put("type", "string")
                .put("description", "Formatting style. Default is iso."));
            properties.put("timezone", new JSONObject()
                .put("type", "string")
                .put("description", "IANA timezone, e.g. Europe/Zurich."));
            properties.put("pattern", new JSONObject()
                .put("type", "string")
                .put("description", "Java SimpleDateFormat pattern."));
            
            params.put("properties", properties);
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String format = args.optString("format", "iso");
        String timezone = args.optString("timezone", "");
        String pattern = args.optString("pattern", "");

        TimeZone tz = timezone.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(timezone);
        long epochMillis = System.currentTimeMillis();
        Date now = new Date(epochMillis);
        
        String formatted;
        if ("locale".equalsIgnoreCase(format)) {
            java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM,
                    java.text.DateFormat.MEDIUM);
            df.setTimeZone(tz);
            formatted = df.format(now);
        } else if ("custom".equalsIgnoreCase(format) && !pattern.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.US);
            sdf.setTimeZone(tz);
            formatted = sdf.format(now);
        } else {
            formatted = formatAsRfc3339(now, tz);
        }
        
        JSONObject result = new JSONObject();
        result.put("datetime", formatted);
        result.put("epochMillis", epochMillis);
        result.put("timezone", tz.getID());
        
        return result.toString();
    }

    @Override
    public boolean requiresApiKey() {
        return false;
    }

    @Override
    public String getApiKeyType() {
        return null;
    }

    /**
     * Format date as RFC3339 string
     */
    private String formatAsRfc3339(Date date, TimeZone timeZone) {
        @SuppressWarnings("SpellCheckingInspection") // yyyy-MM-dd'T'HH:mm:ssZ is correct RFC3339 format
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        sdf.setTimeZone(timeZone);
        String raw = sdf.format(date);
        if (raw.length() > 5) {
            return raw.substring(0, raw.length() - 2) + ":" + raw.substring(raw.length() - 2);
        }
        return raw;
    }
}
