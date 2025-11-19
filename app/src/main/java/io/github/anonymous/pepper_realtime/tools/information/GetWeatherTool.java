package io.github.anonymous.pepper_realtime.tools.information;

import io.github.anonymous.pepper_realtime.network.HttpClientManager;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolContext;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tool for getting weather information using OpenWeatherMap API.
 * Provides current weather and 5-day forecast for specified locations.
 */
@SuppressWarnings("SpellCheckingInspection") // API parameter names like appid, desc, ddT, etc.
public class GetWeatherTool implements Tool {

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Gets the current weather AND the 5-day forecast for a specific location. Always provides both current conditions and future forecast.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("location", new JSONObject()
                .put("type", "string")
                .put("description", "The city or location to get weather information for"));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("location"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String location = args != null ? args.optString("location", "") : "";
        if (location.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "Please provide a location for the weather query.").toString();
        }

        if (!context.getApiKeyManager().isWeatherAvailable()) {
            return new JSONObject().put("success", false).put("error", context.getApiKeyManager().getWeatherSetupMessage()).toString();
        }

        String apiKey = context.getApiKeyManager().getOpenWeatherApiKey();

        // Use optimized shared client for better performance and connection reuse
        OkHttpClient client = HttpClientManager.getInstance().getQuickApiClient();

        String locationQueryParam = "q=" + java.net.URLEncoder.encode(location, "UTF-8");
        String locationInputForName = location;
        String lower = location.toLowerCase();
        if (lower.contains("baden")) {
            locationQueryParam = "lat=47.47&lon=8.30"; // Baden, CH approx
            locationInputForName = "Baden, CH";
        }

        String units = "metric";
        String lang = "en";
        String actualFetchTime = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.US).format(new Date());

        JSONObject currentData = null;
        JSONObject forecastData = null;
        String currentError = null;
        String forecastError = null;

        // Fetch current weather
        try {
            String currentUrl = "https://api.openweathermap.org/data/2.5/weather?" + locationQueryParam + "&appid=" + apiKey + "&units=" + units + "&lang=" + lang;
            Request currentReq = new Request.Builder().url(currentUrl).build();
            try (Response r = client.newCall(currentReq).execute()) {
                okhttp3.ResponseBody responseBody = r.body();
                if (responseBody == null) {
                    currentError = "Failed to fetch current weather: Empty response body";
                } else {
                    String body = responseBody.string();
                    JSONObject data = new JSONObject(body);
                    if (r.isSuccessful() && (data.optInt("cod", 200) == 200 || "200".equals(data.optString("cod")))) {
                        currentData = data;
                    } else {
                        currentError = "Failed to fetch current weather: " + data.optString("message", "API error");
                    }
                }
            }
        } catch (Exception e) {
            currentError = "Network error fetching current weather.";
        }

        // Fetch forecast
        try {
            String forecastUrl = "https://api.openweathermap.org/data/2.5/forecast?" + locationQueryParam + "&appid=" + apiKey + "&units=" + units + "&lang=" + lang;
            Request forecastReq = new Request.Builder().url(forecastUrl).build();
            try (Response r = client.newCall(forecastReq).execute()) {
                okhttp3.ResponseBody responseBody = r.body();
                if (responseBody == null) {
                    forecastError = "Failed to fetch forecast: Empty response body";
                } else {
                    String body = responseBody.string();
                    JSONObject data = new JSONObject(body);
                    if (r.isSuccessful() && (data.optInt("cod", 200) == 200 || "200".equals(data.optString("cod")))) {
                        forecastData = data;
                    } else {
                        forecastError = "Failed to fetch forecast: " + data.optString("message", "API error");
                    }
                }
            }
        } catch (Exception e) {
            forecastError = "Network error fetching forecast.";
        }

        if (currentData == null && forecastData == null) {
            return new JSONObject().put("success", false)
                    .put("error", "Failed to fetch weather data. Current Error: " + currentError + ". Forecast Error: " + forecastError)
                    .toString();
        }

        // Build weather summary
        String locationName;
        if (forecastData != null) {
            JSONObject city = forecastData.optJSONObject("city");
            if (city != null) {
                locationName = city.optString("name", locationInputForName);
            } else {
                locationName = locationInputForName;
            }
        } else {
            locationName = currentData.optString("name", locationInputForName);
        }
        
        int timezoneOffset;
        if (forecastData != null) {
            JSONObject city = forecastData.optJSONObject("city");
            if (city != null) {
                timezoneOffset = city.optInt("timezone", 0);
            } else {
                timezoneOffset = 0;
            }
        } else {
            timezoneOffset = currentData.optInt("timezone", 0);
        }

        long nowSec = System.currentTimeMillis() / 1000L;
        StringBuilder summary = new StringBuilder();
        summary.append("Weather Report for ").append(locationName).append(" (Retrieved: ").append(actualFetchTime).append("):\n\n");

        // Current weather
        if (currentData != null) {
            JSONObject main = currentData.optJSONObject("main");
            JSONArray weatherArr = currentData.optJSONArray("weather");
            JSONObject wind = currentData.optJSONObject("wind");
            int temp = main != null ? (int) Math.round(main.optDouble("temp")) : 0;
            int feels = main != null ? (int) Math.round(main.optDouble("feels_like")) : 0;
            String desc = (weatherArr != null && weatherArr.length() > 0) ? weatherArr.optJSONObject(0).optString("description", "N/A") : "N/A";
            int humidity = main != null ? main.optInt("humidity", 0) : 0;
            double windSpeed = wind != null ? wind.optDouble("speed", 0.0) : 0.0;
            summary.append("**Current (")
                    .append(getReadableTime(currentData.optLong("dt"), timezoneOffset))
                    .append("):** ")
                    .append(temp).append("°C (feels like ").append(feels).append("°C), ")
                    .append(desc)
                    .append(", ").append(humidity).append("% humidity, Wind ").append(windSpeed).append(" m/s.\n\n");
        } else {
            summary.append("**Current:** No data available (").append(currentError).append(").\n\n");
        }

        // Forecast summary (simplified for space)
        if (forecastData != null && forecastData.optJSONArray("list") != null) {
            summary.append("**Forecast:**\n");
            summary.append("(Today is ").append(getReadableDate(nowSec, timezoneOffset)).append(". The following are predictions.)\n\n");

            java.util.Map<String, java.util.List<JSONObject>> byDay = new java.util.LinkedHashMap<>();
            JSONArray list = forecastData.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject item = list.getJSONObject(i);
                    String dateStr = getReadableDate(item.optLong("dt"), timezoneOffset);
                    java.util.List<JSONObject> itemsForDay = byDay.get(dateStr);
                    if (itemsForDay == null) {
                        itemsForDay = new java.util.ArrayList<>();
                        byDay.put(dateStr, itemsForDay);
                    }
                    itemsForDay.add(item);
                }
            }

            java.util.List<String> days = new java.util.ArrayList<>(byDay.keySet());
            if (days.size() > 5) days = days.subList(0, 5);
            for (String day : days) {
                java.util.List<JSONObject> items = byDay.get(day);
                if (items != null) {
                    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
                    java.util.List<String> descs = new java.util.ArrayList<>();
                    for (JSONObject item : items) {
                        JSONObject m = item.optJSONObject("main");
                        if (m != null) {
                            double t = m.optDouble("temp", Double.NaN);
                            if (!Double.isNaN(t)) {
                                min = Math.min(min, t);
                                max = Math.max(max, t);
                            }
                        }
                        JSONArray wArr = item.optJSONArray("weather");
                        if (wArr != null && wArr.length() > 0) {
                            String d = wArr.optJSONObject(0).optString("description", "");
                            if (!d.isEmpty()) descs.add(d);
                        }
                    }
                    int minT = min == Double.POSITIVE_INFINITY ? Integer.MIN_VALUE : (int) Math.round(min);
                    int maxT = max == Double.NEGATIVE_INFINITY ? Integer.MIN_VALUE : (int) Math.round(max);
                    java.util.Set<String> unique = new java.util.LinkedHashSet<>(descs);
                    String cond;
                    if (unique.isEmpty()) {
                        cond = "No specific conditions available";
                    } else {
                        StringBuilder joiner = new StringBuilder();
                        boolean first = true;
                        for (String d : unique) {
                            if (!first) joiner.append(", ");
                            joiner.append(d);
                            first = false;
                        }
                        cond = joiner.toString();
                    }
                    summary.append("**").append(day).append(":**\n");
                    summary.append("  Overall: Min ").append(minT == Integer.MIN_VALUE ? "N/A" : String.valueOf(minT)).append("°C, Max ")
                        .append(maxT == Integer.MIN_VALUE ? "N/A" : String.valueOf(maxT)).append("°C. Conditions: ").append(cond).append(".\n\n");
                }
            }
        } else {
            summary.append("**Forecast:** No data available (").append(forecastError).append(").\n");
        }

        return new JSONObject().put("success", true).put("summary", summary.toString().trim()).toString();
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public String getApiKeyType() {
        return "OpenWeatherMap";
    }

    private String getReadableTime(long epochSeconds, int tzOffsetSeconds) {
        long localMillis = (epochSeconds + tzOffsetSeconds) * 1000L;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(localMillis));
    }

    private String getReadableDate(long epochSeconds, int tzOffsetSeconds) {
        long localMillis = (epochSeconds + tzOffsetSeconds) * 1000L;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(localMillis));
    }
}
