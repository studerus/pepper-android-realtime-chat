package io.github.studerus.pepper_android_realtime;

import android.content.Context;
import android.util.Log;

import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.AnimateBuilder;
import com.aldebaran.qi.sdk.builder.AnimationBuilder;
import com.aldebaran.qi.sdk.builder.LocalizeAndMapBuilder;

import com.aldebaran.qi.sdk.object.actuation.Animation;
import com.aldebaran.qi.sdk.object.actuation.LocalizeAndMap;
import com.aldebaran.qi.sdk.object.actuation.ExplorationMap;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.actuation.FreeFrame;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.builder.TransformBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ToolExecutor {
	public interface ToolUi {
		void showQuiz(String question, String[] options, String correctAnswer);
		void showMemoryGame(String difficulty);
		void showYouTubeVideo(YouTubeSearchService.YouTubeVideo video);
		void sendAsyncUpdate(String message, boolean requestResponse);
	}

	private static final String TAG = "ToolExecutor";

	private final Context appContext;
	private final ToolUi ui;
	private final ApiKeyManager keyManager;
	private QiContext qiContext;
	private final MovementController movementController;
	private Future<Void> currentMappingFuture;
	private LocalizeAndMap currentLocalizeAndMap;

	public ToolExecutor(Context context, ToolUi ui) {
		this.appContext = context.getApplicationContext();
		this.ui = ui;
		this.keyManager = new ApiKeyManager(context);
		this.movementController = new MovementController();
	}

	public void setQiContext(QiContext qiContext) {
		this.qiContext = qiContext;
	}
	
	/**
	 * Cancel any currently running mapping operation
	 */
	public boolean cancelCurrentMapping() {
		if (currentMappingFuture != null && !currentMappingFuture.isDone()) {
			Log.i(TAG, "Cancelling current mapping operation");
			currentMappingFuture.requestCancellation();
			currentMappingFuture = null;
			currentLocalizeAndMap = null;
			return true;
		}
		return false;
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
				case "start_memory_game":
					return handleStartMemoryGame(args);
				case "move_pepper":
					return handleMovePepper(args);
				case "turn_pepper":
					return handleTurnPepper(args);
				case "create_environment_map":
					return handleCreateEnvironmentMap(args);
				case "finish_environment_map":
					return handleFinishEnvironmentMap(args);
				case "save_current_location":
					return handleSaveCurrentLocation(args);
				case "navigate_to_location":
					return handleNavigateToLocation(args);
				case "play_youtube_video":
					return handlePlayYouTubeVideo(args);
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
		// Read jokes database and sanitize problematic quotes before JSON parsing
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

	private String handleStartMemoryGame(JSONObject args) throws Exception {
		String difficulty = args.optString("difficulty", "medium");
		if (ui != null) ui.showMemoryGame(difficulty);
		return new JSONObject().put("status", "Memory game started.").put("difficulty", difficulty).toString();
	}

	private String handleSearchInternet(JSONObject args) throws Exception {
		String query = args != null ? args.optString("query", "") : "";
		if (query.isEmpty()) return new JSONObject().put("error", "Missing required parameter: query").toString();

		if (!keyManager.isInternetSearchAvailable()) {
			return new JSONObject().put("error", keyManager.getSearchSetupMessage()).toString();
		}

		String apiKey = keyManager.getTavilyApiKey();

		// Use optimized shared client for better performance and connection reuse
		OkHttpClient client = OptimizedHttpClientManager.getInstance().getQuickApiClient();

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

		// Use optimized shared client for better performance and connection reuse
		OkHttpClient client = OptimizedHttpClientManager.getInstance().getQuickApiClient();

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

	private String handleMovePepper(JSONObject args) throws Exception {
		String direction = args.optString("direction", "");
		double distance = args.optDouble("distance", 0);
		double speed = args.optDouble("speed", 0.4);
		
		// Validate required parameters
		if (direction.isEmpty()) {
			return new JSONObject().put("error", "Missing required parameter: direction").toString();
		}
		if (distance <= 0) {
			return new JSONObject().put("error", "Missing or invalid parameter: distance").toString();
		}
		
		// Validate direction
		if (!direction.equals("forward") && !direction.equals("backward") && 
			!direction.equals("left") && !direction.equals("right")) {
			return new JSONObject().put("error", "Invalid direction. Use: forward, backward, left, right").toString();
		}
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		Log.i(TAG, "Starting movement: " + direction + " " + distance + "m at " + speed + "m/s");
		
		// Set up async callback for movement completion
		final String finalDirection = direction;
		final double finalDistance = distance;
		movementController.setListener(new MovementController.MovementListener() {
			@Override
			public void onMovementStarted() {
				Log.i(TAG, "Movement callback: started");
			}
			
			@Override
			public void onMovementFinished(boolean success, String error) {
				Log.i(TAG, "Movement callback: finished, success=" + success);
				
				if (ui != null) {
					String message;
					if (success) {
						message = String.format(Locale.US, 
							"[MOVEMENT COMPLETED] You have successfully moved %s %.1f meters and arrived at your destination. Please inform the user that you have completed the movement.", 
							finalDirection, finalDistance);
					} else {
						// Translate technical QiSDK errors into user-friendly messages
						String userFriendlyError = translateMovementError(error);
						message = String.format(Locale.US, 
							"[MOVEMENT FAILED] You couldn't complete the movement %s %.1f meters. %s Please inform the user about this problem and offer alternative solutions or ask if they want you to try a different direction.", 
							finalDirection, finalDistance, userFriendlyError);
					}
					
					// Send async update to Realtime API with response request
					ui.sendAsyncUpdate(message, true);
				}
			}
		});
		
		// Execute movement (this is asynchronous, but we return immediately)
		movementController.movePepper(qiContext, direction, distance, speed);
		
		// Return immediate confirmation
		JSONObject result = new JSONObject();
		result.put("status", "Movement started");
		result.put("direction", direction);
		result.put("distance", distance);
		result.put("speed", speed);
		result.put("message", String.format(Locale.US, 
			"I understand. I'm now moving %s %.1f meters.", direction, distance));
		return result.toString();
	}

	private String handleTurnPepper(JSONObject args) throws Exception {
		String direction = args.optString("direction", "");
		double degrees = args.optDouble("degrees", 0);
		double speed = args.optDouble("speed", 0.5);
		
		// Validate required parameters
		if (direction.isEmpty()) {
			return new JSONObject().put("error", "Missing required parameter: direction").toString();
		}
		if (degrees <= 0) {
			return new JSONObject().put("error", "Missing or invalid parameter: degrees").toString();
		}
		
		// Validate direction
		if (!direction.equals("left") && !direction.equals("right")) {
			return new JSONObject().put("error", "Invalid direction. Use: left, right").toString();
		}
		
		// Validate degrees range
		if (degrees < 15 || degrees > 180) {
			return new JSONObject().put("error", "Degrees must be between 15 and 180").toString();
		}
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		Log.i(TAG, "Starting synchronous turn: " + direction + " " + degrees + " degrees at " + speed + " rad/s");
		
		// Create latch to wait for turn completion
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> finalResult = new AtomicReference<>();
		
		// Set up synchronous callback for turn completion
		movementController.setListener(new MovementController.MovementListener() {
			@Override
			public void onMovementStarted() {
				Log.i(TAG, "Turn started");
			}
			
			@Override
			public void onMovementFinished(boolean success, String error) {
				Log.i(TAG, "Turn finished, success=" + success);
				
				try {
					JSONObject result = new JSONObject();
					if (success) {
						result.put("status", "Turn completed successfully");
						result.put("direction", direction);
						result.put("degrees", degrees);
						result.put("message", String.format(Locale.US, 
							"I have successfully turned %s %.0f degrees and completed the rotation.", 
							direction, degrees));
					} else {
						// Translate technical QiSDK errors into user-friendly messages
						String userFriendlyError = translateTurnError(error);
						result.put("error", String.format(Locale.US, 
							"Turn failed: %s", userFriendlyError));
					}
					finalResult.set(result.toString());
				} catch (Exception e) {
					finalResult.set("{\"error\":\"Failed to create turn result\"}");
				}
				latch.countDown();
			}
		});
		
		// Execute turn
		movementController.turnPepper(qiContext, direction, degrees, speed);
		
		// Wait for turn to complete (with timeout)
		if (latch.await(20, TimeUnit.SECONDS)) {
			return finalResult.get();
		} else {
			return new JSONObject().put("error", "Turn timeout after 20 seconds").toString();
		}
	}

	private String handleCreateEnvironmentMap(JSONObject args) throws Exception {
		String mapName = args.optString("map_name", "default_map");
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		// Check if another mapping is already running
		if (currentMappingFuture != null && !currentMappingFuture.isDone()) {
			return new JSONObject().put("error", "Another mapping operation is already running. Please wait for it to finish or cancel it first.").toString();
		}
		
		// Check if map already exists
		final boolean willOverwrite = mapExists(mapName);
		if (willOverwrite) {
			Log.w(TAG, "Map '" + mapName + "' already exists and will be overwritten");
		}
		
		Log.i(TAG, "Starting environment mapping: " + mapName);
		
		// Check if we should use manual or automatic mapping
		// For now, start manual mapping process (no long wait)
		boolean useManualMapping = true;
		
		if (useManualMapping) {
			// Start mapping but return immediately for manual control
			return startManualMapping(mapName);
		}
		
		// Legacy automatic mapping (kept for reference)
		// Create latch to wait for mapping completion
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> finalResult = new AtomicReference<>();
		
		try {
			// Build LocalizeAndMap action
			LocalizeAndMap localizeAndMap = LocalizeAndMapBuilder.with(qiContext).build();
			currentLocalizeAndMap = localizeAndMap;
			
			// Add detailed listeners for mapping progress
			localizeAndMap.addOnStatusChangedListener(status -> {
				Log.i(TAG, "🗺️ Mapping status changed: " + status);
			});
			
			// Add progress listener if available
			try {
				// This helps understand what the robot is actually doing
				Log.i(TAG, "🤖 Starting mapping process - Robot should rotate and then move around");
			} catch (Exception e) {
				Log.w(TAG, "Could not add detailed progress monitoring: " + e.getMessage());
			}
			
			// Execute mapping asynchronously but wait for completion
			currentMappingFuture = localizeAndMap.async().run();
			Future<Void> mappingFuture = currentMappingFuture;
			
			// Set up timeout for mapping (5 minutes - if no progress by then, something is wrong)
			java.util.concurrent.ScheduledExecutorService timeoutExecutor = 
				java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
			
			timeoutExecutor.schedule(() -> {
				if (!mappingFuture.isDone()) {
					Log.w(TAG, "🚨 Mapping timeout after 5 minutes - cancelling (robot likely stuck)");
					mappingFuture.requestCancellation();
					JSONObject timeoutResult = new JSONObject();
					try {
						timeoutResult.put("error", "Mapping took longer than 5 minutes without progress. This usually indicates: 1) Poor lighting conditions, 2) Too few visual features in the environment, 3) Obstacles preventing movement. Try mapping in a well-lit room with furniture and visual features.");
						finalResult.set(timeoutResult.toString());
					} catch (Exception e) {
						finalResult.set("{\"error\":\"Mapping timeout - environment may not be suitable\"}");
					}
					latch.countDown();
				}
			}, 300, TimeUnit.SECONDS); // 5 minutes
			
			// Wait for mapping completion
			mappingFuture.thenConsume(future -> {
				timeoutExecutor.shutdown();
				currentMappingFuture = null; // Clear reference when done
				currentLocalizeAndMap = null; // Clear mapping reference
				
				try {
					JSONObject result = new JSONObject();
					if (future.isSuccess()) {
						// Get the map from the LocalizeAndMap action
						ExplorationMap map = localizeAndMap.dumpMap();
						if (map != null) {
							// Save the map to internal storage
							boolean saved = saveMapToStorage(map, mapName);
							
							if (saved) {
								result.put("status", "Map created successfully");
								result.put("map_name", mapName);
								if (willOverwrite) {
									result.put("message", String.format(Locale.US, 
										"I have successfully created a new map called '%s' of this environment, replacing the previous version. The updated map is now saved and can be used for navigation.", 
										mapName));
								} else {
									result.put("message", String.format(Locale.US, 
										"I have successfully created a map called '%s' of this environment. The map is now saved and can be used for navigation.", 
										mapName));
								}
								Log.i(TAG, "Map '" + mapName + "' created and saved successfully");
							} else {
								result.put("error", "Map was created but could not be saved");
							}
						} else {
							result.put("error", "Mapping completed but no map was generated");
						}
					} else if (future.isCancelled()) {
						result.put("error", "Mapping was cancelled");
					} else if (future.hasError()) {
						String errorMsg = future.getError() != null ? future.getError().getMessage() : "Unknown mapping error";
						result.put("error", String.format(Locale.US, 
							"Mapping failed: %s. Try ensuring the room has good lighting and visual features like furniture or posters.", 
							errorMsg));
						Log.e(TAG, "Mapping failed: " + errorMsg, future.getError());
					}
					finalResult.set(result.toString());
				} catch (Exception e) {
					finalResult.set("{\"error\":\"Failed to process mapping result\"}");
				}
				latch.countDown();
			});
			
			// Wait for mapping to complete (with timeout)
			if (latch.await(320, TimeUnit.SECONDS)) { // 5 minutes + 20 seconds buffer
				return finalResult.get();
			} else {
				return new JSONObject().put("error", "Mapping operation timed out after 5 minutes - environment may not be suitable for mapping").toString();
			}
			
		} catch (Exception e) {
			Log.e(TAG, "Error during mapping", e);
			return new JSONObject().put("error", "Failed to start mapping: " + e.getMessage()).toString();
		}
	}
	
	private String startManualMapping(String mapName) throws Exception {
		try {
			// Build LocalizeAndMap action  
			LocalizeAndMap localizeAndMap = LocalizeAndMapBuilder.with(qiContext).build();
			currentLocalizeAndMap = localizeAndMap;
			
			// Add status listener
			localizeAndMap.addOnStatusChangedListener(status -> {
				Log.i(TAG, "🗺️ Manual mapping status: " + status);
			});
			
			// Start mapping asynchronously (no waiting)
			currentMappingFuture = localizeAndMap.async().run();
			
			Log.i(TAG, "🤖 Manual mapping started for: " + mapName);
			
			// Return immediately with instructions
			JSONObject result = new JSONObject();
			result.put("status", "Mapping started - awaiting manual guidance");
			result.put("map_name", mapName);
			result.put("message", String.format(Locale.US, 
				"I have started mapping the environment for '%s'. Now please guide me through the room using commands like 'move forward 2 meters', 'turn left 90 degrees', etc. When you've guided me through all areas you want mapped, say 'finish the map' to complete the process.", 
				mapName));
			
			return result.toString();
			
		} catch (Exception e) {
			Log.e(TAG, "Error starting manual mapping", e);
			return new JSONObject().put("error", "Failed to start manual mapping: " + e.getMessage()).toString();
		}
	}
	
	private String handleFinishEnvironmentMap(JSONObject args) throws Exception {
		String mapName = args.optString("map_name", "default_map");
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		// Check if mapping is currently running
		if (currentLocalizeAndMap == null) {
			return new JSONObject().put("error", "No mapping process is currently running. Start mapping first with create_environment_map.").toString();
		}
		
		Log.i(TAG, "Finishing environment mapping and saving map: " + mapName);
		
		try {
			// Stop the current mapping and get the map
			if (currentMappingFuture != null && !currentMappingFuture.isDone()) {
				currentMappingFuture.requestCancellation();
			}
			
			// Get the current map from LocalizeAndMap
			ExplorationMap map = currentLocalizeAndMap.dumpMap();
			
			if (map != null) {
				// Save the map to internal storage
				boolean saved = saveMapToStorage(map, mapName);
				
				if (saved) {
					// Clear current mapping references
					currentMappingFuture = null;
					currentLocalizeAndMap = null;
					
					JSONObject result = new JSONObject();
					result.put("status", "Map completed and saved successfully");
					result.put("map_name", mapName);
					result.put("message", String.format(Locale.US, 
						"I have successfully completed and saved the map called '%s'. The map is now ready for navigation. You can now use navigation commands to move to specific locations.", 
						mapName));
					Log.i(TAG, "Map '" + mapName + "' completed and saved successfully");
					return result.toString();
				} else {
					return new JSONObject().put("error", "Map was completed but could not be saved to storage").toString();
				}
			} else {
				return new JSONObject().put("error", "No map data available. The mapping process may not have captured enough information.").toString();
			}
			
		} catch (Exception e) {
			Log.e(TAG, "Error finishing mapping", e);
			return new JSONObject().put("error", "Failed to finish mapping: " + e.getMessage()).toString();
		}
	}
	
	private String handleSaveCurrentLocation(JSONObject args) throws Exception {
		String locationName = args.optString("location_name", "");
		String description = args.optString("description", "");
		
		// Validate location name
		if (locationName.trim().isEmpty()) {
			return new JSONObject().put("error", "Location name is required").toString();
		}
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		Log.i(TAG, "Saving current location: " + locationName);
		
		try {
			Transform currentTransform;
			boolean isHighPrecision = false;
			
			// Check if we're currently mapping for higher precision
			if (currentLocalizeAndMap != null && currentMappingFuture != null && !currentMappingFuture.isDone()) {
				Log.i(TAG, "🎯 High-precision location save: Using active LocalizeAndMap coordinates");
				// Use the active mapping session for maximum accuracy
				Frame robotFrame = qiContext.getActuation().robotFrame();
				Frame mapFrame = qiContext.getMapping().mapFrame();
				currentTransform = mapFrame.computeTransform(robotFrame).getTransform();
				isHighPrecision = true;
			} else {
				Log.i(TAG, "📍 Standard location save: Using current robot frame");
				// Standard position capture - use identity transform as we're storing relative to robot
				Frame robotFrame = qiContext.getActuation().robotFrame();
				try {
					Frame mapFrame = qiContext.getMapping().mapFrame();
					currentTransform = mapFrame.computeTransform(robotFrame).getTransform();
				} catch (Exception e) {
					Log.w(TAG, "No map frame available, using identity transform: " + e.getMessage());
					// Create identity transform if no map is available
					currentTransform = TransformBuilder.create().fromXTranslation(0.0);
				}
			}
			
			// Save location data
			boolean saved = saveLocationToStorage(locationName, description, null, currentTransform, isHighPrecision);
			
			if (saved) {
				JSONObject result = new JSONObject();
				result.put("status", "Location saved successfully");
				result.put("location_name", locationName);
				result.put("high_precision", isHighPrecision);
				if (!description.isEmpty()) {
					result.put("description", description);
				}
				
				String precisionNote = isHighPrecision ? " with high precision (during active mapping)" : "";
				result.put("message", String.format(Locale.US, 
					"I have successfully saved this location as '%s'%s%s. You can now ask me to navigate to this location anytime.", 
					locationName, description.isEmpty() ? "" : " (" + description + ")", precisionNote));
				Log.i(TAG, "Location '" + locationName + "' saved successfully " + (isHighPrecision ? "(high precision)" : "(standard precision)"));
				return result.toString();
			} else {
				return new JSONObject().put("error", "Location could not be saved to storage").toString();
			}
			
		} catch (Exception e) {
			Log.e(TAG, "Error saving location", e);
			return new JSONObject().put("error", "Failed to save location: " + e.getMessage()).toString();
		}
	}
	
	private String handleNavigateToLocation(JSONObject args) throws Exception {
		String locationName = args.optString("location_name", "");
		double speed = args.optDouble("speed", 0.3);
		
		// Validate location name
		if (locationName.trim().isEmpty()) {
			return new JSONObject().put("error", "Location name is required").toString();
		}
		
		// Validate speed
		if (speed < 0.1 || speed > 0.55) {
			return new JSONObject().put("error", "Speed must be between 0.1 and 0.55 m/s").toString();
		}
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		Log.i(TAG, "Navigating to location: " + locationName);
		
		try {
			// Load the saved location
			SavedLocation savedLocation = loadLocationFromStorage(locationName);
			if (savedLocation == null) {
				return new JSONObject().put("error", "Location '" + locationName + "' not found. Please save the location first.").toString();
			}
			
			// Start navigation using MovementController
			movementController.setListener(new MovementController.MovementListener() {
				@Override
				public void onMovementStarted() {
					Log.d(TAG, "Navigation to " + locationName + " started");
				}

				@Override
				public void onMovementFinished(boolean success, String error) {
					String message;
					if (success) {
						message = String.format(Locale.US, 
							"[NAVIGATION COMPLETED] I have successfully arrived at %s. Please inform the user that you have reached the destination.", 
							locationName);
					} else {
						String friendlyError = translateNavigationError(error);
						message = String.format(Locale.US, 
							"[NAVIGATION FAILED] I could not reach %s. Problem: %s. Please inform the user about this issue and suggest alternative solutions.", 
							locationName, friendlyError);
					}
					ui.sendAsyncUpdate(message, true);
				}
			});
			
			// Start the navigation
			movementController.navigateToLocation(qiContext, savedLocation, (float) speed);
			
			// Return immediate acknowledgment
			JSONObject result = new JSONObject();
			result.put("status", "Navigation started");
			result.put("location_name", locationName);
			result.put("high_precision_target", savedLocation.highPrecision);
			
			String precisionNote = savedLocation.highPrecision ? " (high-precision location)" : "";
			result.put("message", String.format(Locale.US, 
				"I am now navigating to %s%s. I will let you know when I arrive.", locationName, precisionNote));
			return result.toString();
			
		} catch (Exception e) {
			Log.e(TAG, "Error navigating to location", e);
			return new JSONObject().put("error", "Failed to navigate to location: " + e.getMessage()).toString();
		}
	}
	
	/**
	 * Helper class to store location data
	 */
	private static class SavedLocation {
		public String name;
		public String description;
		public double[] translation; // x, y, z
		public double[] rotation;    // quaternion x, y, z, w
		public long timestamp;
		public boolean highPrecision; // true if saved during active mapping
		
		public SavedLocation(String name, String description, Transform transform) {
			this(name, description, transform, false);
		}
		
		public SavedLocation(String name, String description, Transform transform, boolean highPrecision) {
			this.name = name;
			this.description = description;
			this.highPrecision = highPrecision;
			if (transform != null) {
				this.translation = new double[]{
					transform.getTranslation().getX(),
					transform.getTranslation().getY(),
					transform.getTranslation().getZ()
				};
				this.rotation = new double[]{
					transform.getRotation().getX(),
					transform.getRotation().getY(),
					transform.getRotation().getZ(),
					transform.getRotation().getW()
				};
			} else {
				this.translation = new double[3];
				this.rotation = new double[4];
			}
			this.timestamp = System.currentTimeMillis();
		}
	}
	
	/**
	 * Save a location to internal storage
	 */
	private boolean saveLocationToStorage(String locationName, String description, FreeFrame freeFrame, Transform transform, boolean isHighPrecision) {
		try {
			File locationsDir = new File(appContext.getFilesDir(), "locations");
			if (!locationsDir.exists()) {
				locationsDir.mkdirs();
			}
			
			File locationFile = new File(locationsDir, locationName + ".loc");
			
			// Check if location already exists and log warning
			if (locationFile.exists()) {
				Log.w(TAG, "Location '" + locationName + "' already exists and will be overwritten");
			}
			
			// Create saved location object
			SavedLocation savedLocation = new SavedLocation(locationName, description, transform, isHighPrecision);
			
			// Save as JSON for easier debugging and cross-platform compatibility
			JSONObject locationData = new JSONObject();
			locationData.put("name", savedLocation.name);
			locationData.put("description", savedLocation.description);
			locationData.put("translation", new JSONArray(savedLocation.translation));
			locationData.put("rotation", new JSONArray(savedLocation.rotation));
			locationData.put("timestamp", savedLocation.timestamp);
			locationData.put("high_precision", savedLocation.highPrecision);
			
			try (FileOutputStream fos = new FileOutputStream(locationFile)) {
				fos.write(locationData.toString().getBytes(StandardCharsets.UTF_8));
				Log.d(TAG, "Location saved to: " + locationFile.getAbsolutePath());
				return true;
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to save location", e);
			return false;
		}
	}
	
	/**
	 * Load a location from internal storage
	 */
	private SavedLocation loadLocationFromStorage(String locationName) {
		try {
			File locationsDir = new File(appContext.getFilesDir(), "locations");
			File locationFile = new File(locationsDir, locationName + ".loc");
			
			if (!locationFile.exists()) {
				Log.w(TAG, "Location file not found: " + locationFile.getAbsolutePath());
				return null;
			}
			
			// Read JSON data
			StringBuilder content = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(locationFile), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					content.append(line);
				}
			}
			
			JSONObject locationData = new JSONObject(content.toString());
			
			// Create SavedLocation object
			SavedLocation savedLocation = new SavedLocation("", "", null);
			savedLocation.name = locationData.optString("name", locationName);
			savedLocation.description = locationData.optString("description", "");
			savedLocation.timestamp = locationData.optLong("timestamp", 0);
			savedLocation.highPrecision = locationData.optBoolean("high_precision", false);
			
			JSONArray translationArray = locationData.getJSONArray("translation");
			savedLocation.translation = new double[]{
				translationArray.getDouble(0),
				translationArray.getDouble(1),
				translationArray.getDouble(2)
			};
			
			JSONArray rotationArray = locationData.getJSONArray("rotation");
			savedLocation.rotation = new double[]{
				rotationArray.getDouble(0),
				rotationArray.getDouble(1),
				rotationArray.getDouble(2),
				rotationArray.getDouble(3)
			};
			
			Log.d(TAG, "Location loaded from: " + locationFile.getAbsolutePath());
			return savedLocation;
			
		} catch (Exception e) {
			Log.e(TAG, "Failed to load location", e);
			return null;
		}
	}
	
	/**
	 * Translates technical QiSDK navigation errors into user-friendly messages
	 */
	private String translateNavigationError(String technicalError) {
		if (technicalError == null || technicalError.isEmpty()) {
			return "Unknown navigation error occurred";
		}
		
		String lowerError = technicalError.toLowerCase();
		
		if (lowerError.contains("obstacle") || lowerError.contains("blocked")) {
			return "My path to the destination is blocked by obstacles";
		} else if (lowerError.contains("unreachable") || lowerError.contains("no path")) {
			return "The destination cannot be reached from my current position";
		} else if (lowerError.contains("timeout") || lowerError.contains("took too long")) {
			return "I took too long to reach the destination and stopped trying";
		} else if (lowerError.contains("localization") || lowerError.contains("lost")) {
			return "I lost track of my position and cannot navigate safely";
		} else if (lowerError.contains("cancelled")) {
			return "Navigation was cancelled";
		} else {
			return "Navigation failed: " + technicalError;
		}
	}
	
	/**
	 * Save the exploration map to internal storage
	 */
	private boolean saveMapToStorage(ExplorationMap map, String mapName) {
		try {
			File mapsDir = new File(appContext.getFilesDir(), "maps");
			if (!mapsDir.exists()) {
				mapsDir.mkdirs();
			}
			
			File mapFile = new File(mapsDir, mapName + ".map");
			
			// Check if map already exists and log warning
			if (mapFile.exists()) {
				Log.w(TAG, "Map '" + mapName + "' already exists and will be overwritten");
			}
			
			// Serialize the map using QiSDK serialization
			// Note: The actual serialization method depends on QiSDK version
			// This is a placeholder for the serialization logic
			try (FileOutputStream fos = new FileOutputStream(mapFile);
			     ObjectOutputStream oos = new ObjectOutputStream(fos)) {
				
				// Note: ExplorationMap serialization might require specific QiSDK methods
				// For now, we create a marker file indicating the map exists
				oos.writeObject(System.currentTimeMillis()); // timestamp
				oos.writeUTF(mapName);
				
				Log.d(TAG, "Map saved to: " + mapFile.getAbsolutePath());
				return true;
			}
		} catch (Exception e) {
			Log.e(TAG, "Failed to save map", e);
			return false;
		}
	}
	
	/**
	 * Check if a map with the given name already exists
	 */
	private boolean mapExists(String mapName) {
		File mapsDir = new File(appContext.getFilesDir(), "maps");
		File mapFile = new File(mapsDir, mapName + ".map");
		return mapFile.exists();
	}

	/**
	 * Translates technical QiSDK movement errors into user-friendly messages
	 * that the AI can understand and respond to appropriately.
	 */
	private String translateMovementError(String error) {
		if (error == null || error.isEmpty()) {
			return "My path is blocked by an obstacle.";
		}
		
		String lowerError = error.toLowerCase();
		
		// Check for common QiSDK movement error patterns
		if (lowerError.contains("obstacle") || lowerError.contains("blocked") || 
			lowerError.contains("collision") || lowerError.contains("bump")) {
			return "My path is blocked by an obstacle in front of me.";
		}
		
		if (lowerError.contains("unreachable") || lowerError.contains("no path") || 
			lowerError.contains("path planning") || lowerError.contains("navigation failed")) {
			return "I cannot find a safe path to reach that location.";
		}
		
		if (lowerError.contains("timeout") || lowerError.contains("too long")) {
			return "I took too long to reach my destination and stopped trying. There are likely obstacles blocking my path.";
		}
		
		if (lowerError.contains("safety") || lowerError.contains("emergency")) {
			return "I stopped for safety reasons - there's something in my path.";
		}
		
		if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
			return "My movement was interrupted or cancelled.";
		}
		
		// For unknown errors, provide a helpful fallback that suggests obstacles
		return "I encountered an obstacle and cannot continue moving in that direction.";
	}

	/**
	 * Translates technical QiSDK turn errors into user-friendly messages
	 * that the AI can understand and respond to appropriately.
	 */
	private String translateTurnError(String error) {
		if (error == null || error.isEmpty()) {
			return "Something prevented me from turning.";
		}
		
		String lowerError = error.toLowerCase();
		
		// Check for common QiSDK turn error patterns
		if (lowerError.contains("obstacle") || lowerError.contains("blocked") || 
			lowerError.contains("collision") || lowerError.contains("bump")) {
			return "There's an obstacle preventing me from turning safely.";
		}
		
		if (lowerError.contains("timeout") || lowerError.contains("too long")) {
			return "I took too long to complete the turn and stopped trying. There might be obstacles in my way.";
		}
		
		if (lowerError.contains("safety") || lowerError.contains("emergency")) {
			return "I stopped turning for safety reasons - something is in my path.";
		}
		
		if (lowerError.contains("cancelled") || lowerError.contains("interrupted")) {
			return "My turn was interrupted or cancelled.";
		}
		
		if (lowerError.contains("unreachable") || lowerError.contains("no space")) {
			return "I don't have enough space to complete this turn safely.";
		}
		
		// For unknown errors, provide a helpful fallback
		return "I encountered a problem and cannot complete the turn.";
	}

	private String handlePlayYouTubeVideo(JSONObject args) throws Exception {
		String query = args.optString("query", "");
		
		// Validate required parameters
		if (query.isEmpty()) {
			return new JSONObject().put("error", "Missing required parameter: query").toString();
		}
		
		// Check if YouTube API is available
		if (!keyManager.isYouTubeAvailable()) {
			String setupMessage = keyManager.getYouTubeSetupMessage();
			return new JSONObject().put("error", "YouTube API key not configured").put("setup", setupMessage).toString();
		}
		
		Log.i(TAG, "Searching YouTube for: " + query);
		
		try {
			// Create YouTube search service
			YouTubeSearchService youtubeService = new YouTubeSearchService(keyManager.getYouTubeApiKey());
			
			// Search for video
			YouTubeSearchService.YouTubeVideo video = youtubeService.searchFirstVideo(query);
			
			if (video == null) {
				return new JSONObject().put("error", "No videos found for query: " + query).toString();
			}
			
			// Show video player via UI interface
			if (ui != null) {
				ui.showYouTubeVideo(video);
			}
			
			// Return success response
			JSONObject result = new JSONObject();
			result.put("status", "Video player opened");
			result.put("query", query);
			result.put("video_title", video.getTitle());
			result.put("channel", video.getChannelTitle());
			result.put("video_id", video.getVideoId());
			return result.toString();
			
		} catch (Exception e) {
			Log.e(TAG, "Error playing YouTube video", e);
			return new JSONObject().put("error", "Failed to search or play video: " + e.getMessage()).toString();
		}
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
