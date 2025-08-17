package com.example.pepper_test2;

import android.content.Context;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;

import com.aldebaran.qi.sdk.object.actuation.Animation;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ToolExecutor {
	public interface ToolUi {
		void showQuiz(String question, String[] options, String correctAnswer);
	}

	private static final String TAG = "ToolExecutor";

	private final Context appContext;
	private final ToolUi ui;
	private final ApiKeyManager keyManager;
	private QiContext qiContext;

	public ToolExecutor(Context context, ToolUi ui) {
		this.appContext = context.getApplicationContext();
		this.ui = ui;
		this.keyManager = new ApiKeyManager(context);
	}

	public void setQiContext(QiContext qiContext) {
		this.qiContext = qiContext;
	}

	public String execute(String toolName, JSONObject args) {
		try {
			switch (toolName) {
				case "get_current_datetime":
					return handleGetCurrentDatetime(args);
				case "play_animation":
					return handlePlayAnimation(args);
				case "present_quiz_question":
					return handlePresentQuiz(args);
				case "get_random_joke":
					return handleGetRandomJoke();
				case "search_internet":
					return handleSearchInternet(args);
				case "get_weather":
					return handleGetWeather(args);
				default:
					return new JSONObject().put("error", "Unknown tool: " + toolName).toString();
			}
		} catch (Exception e) {
			Log.e(TAG, "Tool execution error", e);
			try { return new JSONObject().put("error", e.getMessage()).toString(); }
			catch (Exception ignored) { return "{\"error\":\"execution failed\"}"; }
		}
	}

	private String handleGetRandomJoke() throws Exception {
		// Try to read jokes.json from assets root
		String json = readJokesFromAssets();
		if (json == null || json.isEmpty()) {
			return new JSONObject().put("error", "jokes.json not found").toString();
		}
		JSONObject root = new JSONObject(json);
		JSONArray arr = root.optJSONArray("jokes");
		if (arr == null || arr.length() == 0) return new JSONObject().put("error", "No jokes available").toString();
		Random rnd = new Random();
		JSONObject joke = arr.getJSONObject(rnd.nextInt(arr.length()));
		JSONObject result = new JSONObject();
		result.put("id", joke.optInt("id"));
		result.put("text", joke.optString("text"));
		return result.toString();
	}

	private String readJokesFromAssets() {
		try {
			InputStream is = appContext.getAssets().open("jokes.json");
			BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) sb.append(line).append('\n');
			br.close();
			is.close();
			return sb.toString();
		} catch (Exception e) {
			Log.e(TAG, "readJokesFromAssets failed: jokes.json", e);
			return null;
		}
	}

	private String handleGetCurrentDatetime(JSONObject args) throws Exception {
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

	private String handlePlayAnimation(JSONObject args) throws Exception {
		String name = args.optString("name", "");
		if (name.isEmpty()) return new JSONObject().put("error", "Missing required parameter: name").toString();
		Integer resId = mapAnimationNameToResId(name);
		if (resId == null) return new JSONObject().put("error", "Unsupported animation name: " + name).toString();
		if (qiContext == null) return new JSONObject().put("error", "QiContext not ready").toString();
		try {
			Future<Animation> animFuture = AnimationBuilder.with(qiContext).withResources(resId).buildAsync();
			animFuture.andThenCompose(animation ->
					AnimateBuilder.with(qiContext).withAnimation(animation).build().async().run()
			).thenConsume(f -> { if (f.hasError()) Log.e(TAG, "Animation failed", f.getError()); });
		} catch (Exception e) {
			Log.e(TAG, "Error starting animation", e);
		}
		return new JSONObject().put("status", "started").put("name", name).toString();
	}

	private String handlePresentQuiz(JSONObject args) throws Exception {
		String question = args.optString("question", "");
		JSONArray optionsJson = args.optJSONArray("options");
		String correct = args.optString("correct_answer", "");
		if (question.isEmpty() || optionsJson == null || optionsJson.length() != 4) {
			return new JSONObject().put("error", "Missing required parameters: question or 4 options").toString();
		}
		String[] opts = new String[4];
		for (int i = 0; i < 4; i++) opts[i] = optionsJson.getString(i);
		if (ui != null) ui.showQuiz(question, opts, correct);
		return new JSONObject().put("status", "Quiz presented to user.").toString();
	}

	private String handleSearchInternet(JSONObject args) throws Exception {
		String query = args != null ? args.optString("query", "") : "";
		if (query.isEmpty()) return new JSONObject().put("error", "Missing required parameter: query").toString();

		if (!keyManager.isInternetSearchAvailable()) {
			return new JSONObject().put("error", keyManager.getSearchSetupMessage()).toString();
		}

		String apiKey = keyManager.getTavilyApiKey();

		OkHttpClient client = new OkHttpClient.Builder()
				.connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
				.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
				.writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
				.build();

		JSONObject payload = new JSONObject();
		payload.put("api_key", apiKey);
		payload.put("query", query);
		payload.put("search_depth", "basic");
		payload.put("include_answer", true);
		payload.put("max_results", 3);

		RequestBody body = RequestBody.create(payload.toString(), MediaType.parse("application/json"));
		Request request = new Request.Builder()
				.url("https://api.tavily.com/search")
				.post(body)
				.addHeader("Content-Type", "application/json")
				.build();

		try (Response response = client.newCall(request).execute()) {
			if (!response.isSuccessful()) {
				return new JSONObject().put("error", "Internet search failed: HTTP " + response.code()).toString();
			}
			okhttp3.ResponseBody responseBody = response.body();
			if (responseBody == null) {
				return new JSONObject().put("error", "Internet search failed: Empty response body").toString();
			}
			String resp = responseBody.string();
			JSONObject data = new JSONObject(resp);
			String result = data.optString("answer", "");
			if (result.isEmpty()) {
				JSONArray results = data.optJSONArray("results");
				if (results != null && results.length() > 0) {
					JSONObject first = results.getJSONObject(0);
					result = first.optString("content", "");
				}
			}
			if (result.isEmpty()) {
				result = "Sorry, I couldn't find any information about that.";
			}
			return new JSONObject().put("answer", result).toString();
		} catch (Exception e) {
			Log.e(TAG, "Error in internet search", e);
			return new JSONObject().put("error", e.getMessage() != null ? e.getMessage() : "Failed to search internet").toString();
		}
	}

	@SuppressWarnings("SpellCheckingInspection") // API parameter names like appid, desc, ddT, etc.
	private String handleGetWeather(JSONObject args) throws Exception {
		String location = args != null ? args.optString("location", "") : "";
		if (location.isEmpty()) return new JSONObject().put("success", false).put("error", "Please provide a location for the weather query.").toString();

		if (!keyManager.isWeatherAvailable()) {
			return new JSONObject().put("success", false).put("error", keyManager.getWeatherSetupMessage()).toString();
		}

		String apiKey = keyManager.getOpenWeatherApiKey();

		OkHttpClient client = new OkHttpClient.Builder()
				.connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
				.readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
				.writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
				.build();

		String locationQueryParam = "q=" + java.net.URLEncoder.encode(location, "UTF-8");
		String locationInputForName = location;
		String lower = location.toLowerCase();
		if (lower.contains("baden")) {
			locationQueryParam = "lat=47.47&lon=8.30"; // Baden, CH approx
			locationInputForName = "Baden, CH";
		}

		String units = "metric";
		String lang = "en";
		String actualFetchTime = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM, Locale.US).format(new java.util.Date());

		JSONObject currentData = null;
		JSONObject forecastData = null;
		String currentError = null;
		String forecastError = null;

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

		String locationName;
		if (forecastData != null) {
			JSONObject city = forecastData.optJSONObject("city");
			if (city != null) {
				locationName = city.optString("name", locationInputForName);
			} else {
				locationName = locationInputForName;
			}
		} else {
			// At this point, currentData must be non-null due to the check above
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
			// At this point, currentData must be non-null due to the check above
			timezoneOffset = currentData.optInt("timezone", 0);
		}

		long nowSec = System.currentTimeMillis() / 1000L;
		StringBuilder summary = new StringBuilder();
		summary.append("Weather Report for ").append(locationName).append(" (Retrieved: ").append(actualFetchTime).append("):\n\n");

		if (currentData != null) {
			JSONObject main = currentData.optJSONObject("main");
			JSONArray weatherArr = currentData.optJSONArray("weather");
			JSONObject wind = currentData.optJSONObject("wind");
			int temp = main != null ? (int) Math.round(main.optDouble("temp")) : 0;
			int feels = main != null ? (int) Math.round(main.optDouble("feels_like")) : 0;
			String desc = (weatherArr != null && weatherArr.length() > 0) ? weatherArr.optJSONObject(0).optString("description", "N/A") : "N/A";
			int humidity = main != null ? main.optInt("humidity", 0) : 0;
			double windSpeed = wind != null ? wind.optDouble("speed", 0.0) : 0.0;
			summary.append("**Current ("
					).append(getReadableTime(currentData.optLong("dt"), timezoneOffset))
					.append("):** ")
					.append(temp).append("°C (feels like ").append(feels).append("°C), ")
					.append(desc)
					.append(", ").append(humidity).append("% humidity, Wind ").append(windSpeed).append(" m/s.\n\n");
		} else {
			summary.append("**Current:** No data available (").append(currentError).append(").\n\n");
		}

		if (forecastData != null && forecastData.optJSONArray("list") != null) {
			summary.append("**Forecast:**\n");
			summary.append("(");
			summary.append("Today is ").append(getReadableDate(nowSec, timezoneOffset)).append(". The following are predictions.");
			summary.append(")\n\n");

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

	private String getReadableTime(long epochSeconds, int tzOffsetSeconds) {
		long localMillis = (epochSeconds + tzOffsetSeconds) * 1000L;
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", Locale.US);
		sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		return sdf.format(new java.util.Date(localMillis));
	}

	private String getReadableDate(long epochSeconds, int tzOffsetSeconds) {
		long localMillis = (epochSeconds + tzOffsetSeconds) * 1000L;
		java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.US);
		sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
		return sdf.format(new java.util.Date(localMillis));
	}

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

	@SuppressWarnings("SpellCheckingInspection") // Animation names like bowshort, showfloor, showsky, showtablet
	private Integer mapAnimationNameToResId(String name) {
		switch (name) {
			case "applause_01": return R.raw.applause_01;
			case "bowshort_01": return R.raw.bowshort_01;
			case "funny_01": return R.raw.funny_01;
			case "happy_01": return R.raw.happy_01;
			case "hello_01": return R.raw.hello_01;
			case "hey_02": return R.raw.hey_02;
			case "kisses_01": return R.raw.kisses_01;
			case "laugh_01": return R.raw.laugh_01;
			case "showfloor_01": return R.raw.showfloor_01;
			case "showsky_01": return R.raw.showsky_01;
			case "showtablet_02": return R.raw.showtablet_02;
			case "wings_01": return R.raw.wings_01;
			default: return null;
		}
	}
}
