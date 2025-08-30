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
import com.aldebaran.qi.sdk.object.actuation.Localize;
import com.aldebaran.qi.sdk.object.actuation.LocalizationStatus;
import com.aldebaran.qi.sdk.object.actuation.Frame;
// import com.aldebaran.qi.sdk.object.actuation.FreeFrame; // removed, no longer used
import com.aldebaran.qi.sdk.object.geometry.Transform;
import com.aldebaran.qi.sdk.builder.TransformBuilder;
import com.aldebaran.qi.sdk.builder.ExplorationMapBuilder;
import com.aldebaran.qi.sdk.object.streamablebuffer.StreamableBuffer;
import com.aldebaran.qi.sdk.builder.LocalizeBuilder;
import com.aldebaran.qi.sdk.object.power.Power;
import com.aldebaran.qi.sdk.object.power.FlapSensor;
import com.aldebaran.qi.sdk.object.power.FlapState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
// import java.io.InputStream; // unused
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;
// import java.io.ObjectOutputStream; // unused
import java.io.FileInputStream;
// import java.io.ObjectInputStream; // unused
import java.nio.charset.StandardCharsets;
// import java.io.FileWriter; // unused
import java.io.BufferedWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
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
	
	// Interface for status updates
	public interface StatusUpdateListener {
		void onMapStatusChanged(String status);
		void onLocalizationStatusChanged(String status);
	}

	private static final String TAG = "ToolExecutor";

	private final Context appContext;
	private final ToolUi ui;
	private StatusUpdateListener statusUpdateListener;
	private final ApiKeyManager keyManager;
	private QiContext qiContext;
	private final MovementController movementController;
	private Future<Void> currentMappingFuture;
	private LocalizeAndMap currentLocalizeAndMap;
	private Future<Void> currentLocalizeFuture;
	// Startup readiness flags and cache
	private volatile boolean mapLoaded = false;
	private volatile boolean localizationReady = false;
	private volatile boolean localizationInProgress = false;
	private volatile String localizationStatus = "WAITING"; // WAITING, STARTING, RUNNING, ERROR, STOPPED
	private volatile ExplorationMap cachedMap = null; // Cache loaded map to avoid reloading
	@SuppressWarnings({"FieldCanBeLocal", "unused"})
//	private volatile boolean navInitStarted = false; // removed for clean startup orchestration (now in ChatActivity)
	// Single authoritative map name used across the app
	private static final String ACTIVE_MAP_NAME = "default_map";

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
	 * Initialize navigation on app start: preload map and run a localization once.
	 * Returns immediately; completion can be observed via readiness getters.
	 */
// Intentionally no startNavigationInitializerAsync here; orchestrated by ChatActivity

	// Readiness flags for orchestrator (setters only; getters removed to keep API minimal)
	public void setMapLoadedFlag(boolean loaded) { 
		this.mapLoaded = loaded; 
		if (!loaded) {
			this.cachedMap = null; // Clear cache when map is unloaded
		}
	}
	
	public void setCachedMap(ExplorationMap map) {
		this.cachedMap = map;
	}
	public void setLocalizationReadyFlag(boolean ready) { this.localizationReady = ready; }
	
	public void setLocalizationStatus(String status) { 
		this.localizationStatus = status; 
		if (statusUpdateListener != null) {
			statusUpdateListener.onLocalizationStatusChanged(status);
		}
	}
	
	public void setStatusUpdateListener(StatusUpdateListener listener) {
		this.statusUpdateListener = listener;
	}
	
	public boolean isLocalizationReady() { return this.localizationReady; }
	
	public boolean isLocalizationInProgress() { return this.localizationInProgress; }
	
	/**
	 * Start localization on-demand for navigation requests
	 * Returns a Promise that resolves when localization is ready
	 */
	public com.aldebaran.qi.Promise<Boolean> startLocalizationOnDemand() {
		final com.aldebaran.qi.Promise<Boolean> promise = new com.aldebaran.qi.Promise<>();
		
		// Check if already ready
		if (localizationReady) {
			promise.setValue(true);
			return promise;
		}
		
		// Check if already in progress
		if (localizationInProgress) {
			// Wait for current localization to complete
			new Thread(() -> {
				try {
					// Poll until localization is done (max 30 seconds)
					int attempts = 0;
					while (localizationInProgress && attempts < 300) { // 30 seconds / 100ms intervals
						Thread.sleep(100);
						attempts++;
						if (localizationReady) {
							promise.setValue(true);
							return;
						}
					}
					// Timeout or failed
					promise.setValue(false);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					promise.setValue(false);
				}
			}).start();
			return promise;
		}
		
		// Check prerequisites
		if (qiContext == null) {
			promise.setValue(false);
			return promise;
		}
		
		if (!mapLoaded) {
			promise.setValue(false);
			return promise;
		}
		
		// Start localization
		localizationInProgress = true;
		setLocalizationStatus("STARTING");
		
		new Thread(() -> {
			try {
				// Use cached map if available, otherwise load from storage
				ExplorationMap map = cachedMap;
				if (map == null) {
					Log.i(TAG, "Loading map from storage for localization...");
					map = loadMap(ACTIVE_MAP_NAME);
					if (map == null) {
						localizationInProgress = false;
						setLocalizationStatus("ERROR");
						promise.setValue(false);
			return;
					}
					// Cache the loaded map for future use
					cachedMap = map;
				} else {
					Log.i(TAG, "Using cached map for localization (avoiding reload)");
				}
				
				// Start localization
				Localize localize = LocalizeBuilder.with(qiContext).withMap(map).build();
				Future<Void> localizeFuture = localize.async().run();
				
				// Set up status listener
				localize.addOnStatusChangedListener(status -> {
					Log.i(TAG, "On-demand localization status: " + status);
					
					if (status == LocalizationStatus.LOCALIZED) {
						Log.i(TAG, "On-demand localization successful");
						localizationReady = true;
						localizationInProgress = false;
						setLocalizationStatus("RUNNING");
						promise.setValue(true);
						
					} else if (status == LocalizationStatus.SCANNING) {
						Log.i(TAG, "Robot scanning for on-demand localization...");
						// Keep waiting
						
					} else {
						Log.w(TAG, "On-demand localization failed with status: " + status);
						localizationReady = false;
						localizationInProgress = false;
						setLocalizationStatus("ERROR");
						promise.setValue(false);
					}
				});
				
				// Timeout safety net
		new Thread(() -> {
			try {
						Thread.sleep(30000); // 30 second timeout
						if (localizationInProgress) {
							Log.w(TAG, "On-demand localization timeout");
							localizationReady = false;
							localizationInProgress = false;
							setLocalizationStatus("ERROR");
							promise.setValue(false);
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}).start();
				
				// Monitor future
				localizeFuture.thenConsume(future -> {
					if (!future.isSuccess()) {
						String error = future.getError() != null ? future.getError().getMessage() : "Unknown error";
						Log.w(TAG, "On-demand localization future failed: " + error);
						if (localizationInProgress) { // Only update if still in progress
							localizationReady = false;
							localizationInProgress = false;
							setLocalizationStatus("ERROR");
							promise.setValue(false);
						}
					}
				});
				
			} catch (Exception e) {
				Log.e(TAG, "Failed to start on-demand localization", e);
				localizationInProgress = false;
				setLocalizationStatus("ERROR");
				promise.setValue(false);
			}
		}).start();
		
		return promise;
	}
	
	/**
	 * Start localization and navigation asynchronously in the background
	 * This method returns immediately while localization and navigation run in background
	 */
	private void startAsyncLocalizationAndNavigation(String locationName, double speed) {
		new Thread(() -> {
			try {
				Log.i(TAG, "Background: Starting localization for navigation to: " + locationName);
				
				// Start localization asynchronously
				com.aldebaran.qi.Promise<Boolean> localizationPromise = startLocalizationOnDemand();
				
				// Use callback instead of blocking
				localizationPromise.getFuture().thenConsume(future -> {
					if (future.isSuccess() && future.getValue()) {
						// Localization successful, proceed with navigation
						Log.i(TAG, "Background: Localization successful, starting navigation to: " + locationName);
						
						// Inform user that localization succeeded and navigation is starting
						if (ui != null) {
							ui.sendAsyncUpdate("[SYSTEM UPDATE] The robot has successfully localized and is now navigating to " + locationName + ". Please inform the user about this progress.", true);
						}
						
						try {
							// Load the saved location
							SavedLocation savedLocation = loadLocationFromStorage(locationName);
														if (savedLocation == null) {
								if (ui != null) {
									ui.sendAsyncUpdate("[SYSTEM ERROR] The robot could not find the saved location '" + locationName + "'. Please inform the user that the location needs to be saved first using 'save current location'.", true);
								}
								return;
							}
				
							// Start navigation using MovementController
							movementController.setListener(new MovementController.MovementListener() {
								@Override
								public void onMovementStarted() {
									Log.d(TAG, "Background navigation to " + locationName + " started");
									// Movement started message removed - already sent above after localization success
								}

								@Override
								public void onMovementFinished(boolean success, String error) {
									if (success) {
										Log.i(TAG, "Background navigation to " + locationName + " completed successfully");
										if (ui != null) {
											ui.sendAsyncUpdate("[NAVIGATION COMPLETED] The robot has successfully arrived at " + locationName + ". Please inform the user that you have reached the destination.", true);
										}
									} else {
										String friendlyError = translateNavigationError(error);
										Log.w(TAG, "Background navigation to " + locationName + " failed: " + friendlyError);
										if (ui != null) {
											ui.sendAsyncUpdate("[NAVIGATION FAILED] The robot could not reach " + locationName + ". Problem: " + friendlyError + ". Please inform the user about this issue and suggest alternative solutions.", true);
										}
									}
								}
							});

							movementController.navigateToLocation(qiContext, savedLocation, (float) speed);
							
						} catch (Exception e) {
							Log.e(TAG, "Error during background navigation", e);
							if (ui != null) {
								ui.sendAsyncUpdate("[SYSTEM ERROR] The robot encountered an error while trying to navigate: " + e.getMessage() + ". Please inform the user to try again or suggest creating a new map.", true);
							}
						}
						
					} else {
						// Localization failed
						Log.w(TAG, "Background: Localization failed for navigation to: " + locationName);
						if (ui != null) {
							ui.sendAsyncUpdate("[LOCALIZATION FAILED] The robot is having trouble recognizing its surroundings and cannot navigate to " + locationName + ". Please inform the user that a new map may be needed or the robot should be moved to a more recognizable position. Suggest using 'create environment map' to create a new map.", true);
						}
					}
				});
				
			} catch (Exception e) {
				Log.e(TAG, "Error during background localization", e);
				if (ui != null) {
					ui.sendAsyncUpdate("[SYSTEM ERROR] The robot encountered an error during localization: " + e.getMessage() + ". Please inform the user to try again or suggest restarting the app.", true);
				}
			}
		}).start();
	}

	// Expose map loading for ChatActivity orchestration
	public ExplorationMap loadMap(String mapName) { return loadMapFromStorage(mapName); }

	// Start a single localization with the provided map and report success via Future<Boolean>
	public com.aldebaran.qi.Future<Boolean> localizeOnceAsync(ExplorationMap map) {
		com.aldebaran.qi.Promise<Boolean> p = new com.aldebaran.qi.Promise<>();
		try {
			Localize localize = LocalizeBuilder.with(qiContext).withMap(map).build();
			currentLocalizeFuture = localize.async().run();
			currentLocalizeFuture.thenConsume(f -> {
				currentLocalizeFuture = null;
				p.setValue(f.isSuccess());
			});
			} catch (Exception e) {
			p.setError(e.getMessage() != null ? e.getMessage() : "localize failed");
		}
		return p.getFuture();
	}


	
	/**
	 * Start a Localize action to maintain position tracking after mapping is complete
	 * This prevents odometry drift during navigation
	 */
	private void startLocalizationSession(ExplorationMap map) {
		try {
			Log.i(TAG, "Starting localization session to maintain position tracking...");
			
			// Build Localize action with the current map
			Localize localize = LocalizeBuilder.with(qiContext)
					.withMap(map)
					.build();
			
			// Start localization asynchronously
			currentLocalizeFuture = localize.async().run();
			currentLocalizeFuture.thenConsume(future -> {
				if (future.isSuccess()) {
					Log.i(TAG, "✅ Localization session started - robot will maintain accurate position tracking");
				} else {
					String error = future.getError() != null ? future.getError().getMessage() : "Unknown error";
					Log.w(TAG, "❌ Failed to start localization session: " + error);
					Log.w(TAG, "Navigation may be less accurate due to odometry drift");
				}
				currentLocalizeFuture = null;
			});
			
		} catch (Exception e) {
			Log.e(TAG, "Error starting localization session", e);
		}
	}
	
	// Removed obsolete cancelCurrentMapping(): manual mapping now ends in finish_environment_map

	/**
	 * Stop any running Localize action to avoid conflicts with LocalizeAndMap
	 */
	private void stopLocalizationIfRunning() {
		try {
			if (currentLocalizeFuture != null && !currentLocalizeFuture.isDone()) {
				Log.i(TAG, "Stopping active Localize action to avoid conflicts with mapping");
				currentLocalizeFuture.requestCancellation();
			}
		} catch (Exception e) {
			Log.w(TAG, "Error while stopping Localize action", e);
		} finally {
			currentLocalizeFuture = null;
		}
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
					return handleCreateEnvironmentMap();
				case "finish_environment_map":
					return handleFinishEnvironmentMap();
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
			try (java.io.InputStream is = appContext.getAssets().open("jokes.json");
			     BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) sb.append(line).append('\n');
			return sb.toString();
			}
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
		
		// Check if charging flap is open (prevents movement for safety)
		if (isChargingFlapOpen(qiContext)) {
			return new JSONObject().put("error", "Cannot move while charging flap is open. Please close the charging flap first for safety.").toString();
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
		
		// Check if charging flap is open (prevents movement for safety)
		if (isChargingFlapOpen(qiContext)) {
			return new JSONObject().put("error", "Cannot turn while charging flap is open. Please close the charging flap first for safety.").toString();
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

	private String handleCreateEnvironmentMap() throws Exception {
		// Enforce single active map name
		String mapName = ACTIVE_MAP_NAME;
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		// Check if charging flap is open (mapping requires movement)
		if (isChargingFlapOpen(qiContext)) {
			return new JSONObject().put("error", "Cannot create map while charging flap is open. Mapping requires robot movement. Please close the charging flap first.").toString();
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
		// Ensure no Localize is running (QiSDK forbids running Localize and LocalizeAndMap together)
		stopLocalizationIfRunning();
		// Mapping replaces the active map; mark navigation readiness as not ready until finalized
		this.mapLoaded = false;
		this.localizationReady = false;
		
		// Start manual mapping process and return immediately for manual guidance
			return startManualMapping(mapName);
		
		// unreachable legacy automatic branch removed for clean code
	}
	
	private String startManualMapping(String mapName) throws Exception {
		try {
			// Clear all existing locations when starting a new map
			// New map = new coordinate system = old locations become invalid
			List<String> deletedLocations = clearAllLocations();
			
			// Build LocalizeAndMap action  
			LocalizeAndMap localizeAndMap = LocalizeAndMapBuilder.with(qiContext).build();
			currentLocalizeAndMap = localizeAndMap;
			
			// Add status listener
			localizeAndMap.addOnStatusChangedListener(status -> Log.i(TAG, "🗺️ Manual mapping status: " + status));
			
			// Start mapping asynchronously (no waiting)
			currentMappingFuture = localizeAndMap.async().run();
			
			Log.i(TAG, "🤖 Manual mapping started for: " + mapName);
			
			// Return immediately with instructions
			JSONObject result = new JSONObject();
			result.put("status", "Mapping started - awaiting manual guidance");
			// map_name omitted because a single fixed map name is used globally
			
			String deletionInfo = "";
			if (!deletedLocations.isEmpty()) {
				deletionInfo = String.format(Locale.US, " I have cleared %d existing locations (%s) since they would be invalid with the new map coordinate system.", 
					deletedLocations.size(), buildCommaList(deletedLocations));
			}
			
			result.put("message", String.format(Locale.US, 
				"I have started mapping the environment for '%s'.%s Now please guide me through the room using commands like 'move forward 2 meters', 'turn left 90 degrees', etc. When you've guided me through all areas you want mapped, say 'finish the map' to complete the process.", 
				mapName, deletionInfo));
			
			return result.toString();
			
		} catch (Exception e) {
			Log.e(TAG, "Error starting manual mapping", e);
			return new JSONObject().put("error", "Failed to start manual mapping: " + e.getMessage()).toString();
		}
	}
	
	private static String buildCommaList(java.util.List<String> items) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) sb.append(", ");
			sb.append(items.get(i));
		}
		return sb.toString();
	}
	
	private String handleFinishEnvironmentMap() throws Exception {
		// Enforce single active map name
		String mapName = ACTIVE_MAP_NAME;
		
		// Check if robot is ready
		if (qiContext == null) {
			return new JSONObject().put("error", "Robot not ready").toString();
		}
		
		// Check if mapping is currently running
		if (currentLocalizeAndMap == null) {
			return new JSONObject().put("error", "No mapping process is currently running. Start mapping first with create_environment_map.").toString();
		}
		
		Log.i(TAG, "Finishing environment mapping and saving map: " + mapName);
		
		// Perform heavy finalization asynchronously to avoid blocking WebSocket threads
		new Thread(() -> {
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
					
					// Start Localize action to maintain position tracking after mapping
					startLocalizationSession(map);
					// Update readiness flags for navigation after successful map save and localization start
					mapLoaded = true;
					localizationReady = true;
					
						if (ui != null) {
							ui.sendAsyncUpdate(String.format(Locale.US,
								"[MAPPING COMPLETED] I have successfully completed and saved the map called '%s'. The map is now ready for navigation.",
								mapName), false);
						}
					Log.i(TAG, "Map '" + mapName + "' completed and saved successfully");
				} else {
						if (ui != null) {
							ui.sendAsyncUpdate("[MAPPING ERROR] Map was completed but could not be saved to storage.", false);
						}
						Log.w(TAG, "Map was completed but could not be saved to storage");
				}
			} else {
					if (ui != null) {
						ui.sendAsyncUpdate("[MAPPING ERROR] No map data available. The mapping process may not have captured enough information.", false);
			}
					Log.w(TAG, "No map data available after mapping");
				}
		} catch (Exception e) {
				Log.e(TAG, "Error finishing mapping (async)", e);
				if (ui != null) {
					ui.sendAsyncUpdate("[MAPPING ERROR] Failed to finish mapping: " + e.getMessage(), false);
				}
			}
		}, "map-finalizer").start();
		
		// Return immediately to keep WebSocket responsive
		JSONObject result = new JSONObject();
		result.put("status", "Map finalization started");
		// map_name omitted because a single fixed map name is used globally
		result.put("message", String.format(Locale.US,
			"I'm finalizing and saving the map '%s' now. I'll let you know when it's ready.", mapName));
		return result.toString();
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
				currentTransform = robotFrame.computeTransform(mapFrame).getTransform();
				isHighPrecision = true;
			} else {
				Log.i(TAG, "📍 Standard location save: Using current robot frame");
				// Standard position capture - use identity transform as we're storing relative to robot
				Frame robotFrame = qiContext.getActuation().robotFrame();
				try {
					Frame mapFrame = qiContext.getMapping().mapFrame();
					currentTransform = robotFrame.computeTransform(mapFrame).getTransform();
				} catch (Exception e) {
					Log.w(TAG, "No map frame available, using identity transform: " + e.getMessage());
					// Create identity transform if no map is available
					currentTransform = TransformBuilder.create().fromXTranslation(0.0);
				}
			}
			
			// Save location data
			boolean saved = saveLocationToStorage(locationName, description, currentTransform, isHighPrecision);
			
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
		
		// Check if charging flap is open (prevents movement for safety)
		if (isChargingFlapOpen(qiContext)) {
			return new JSONObject().put("error", "Cannot navigate while charging flap is open. Please close the charging flap first for safety.").toString();
		}
		
		// Check if map is loaded
		if (!mapLoaded) {
			return new JSONObject().put("error", "I'm sorry, I'm still loading my map and need a moment before I can navigate. Please try again in a minute.").toString();
		}
		
		// Check if localization is ready or needs to be started
		if (!localizationReady) {
			// If localization is already in progress, return appropriate message
			if (localizationInProgress) {
				return new JSONObject().put("error", "I'm sorry, I'm currently getting my bearings and need a moment before I can navigate. Please wait a moment and try again.").toString();
			}
			
			// Start asynchronous localization and navigation
			Log.i(TAG, "Starting asynchronous localization and navigation to: " + locationName);
			
			// Inform user immediately that localization is starting
			if (ui != null) {
				ui.sendAsyncUpdate("[SYSTEM STATUS] The robot is getting its bearings before navigating to " + locationName + ". Please inform the user to wait a moment.", false);
			}
			
			// Start localization and navigation in background
			startAsyncLocalizationAndNavigation(locationName, speed);
			
			// Return immediate success response
			return new JSONObject().put("success", "I'll get my bearings and then navigate to " + locationName + ". Please wait a moment.").toString();
		}

		// Check if map frame is available; if not, fail fast (safety)
		if (!isMapAvailable(qiContext)) {
			return new JSONObject().put("error", "Navigation not available. Robot mapping system needs to be initialized. Please create a new map first or wait for map restoration to complete.").toString();
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
	private boolean saveLocationToStorage(String locationName, String description, Transform transform, boolean isHighPrecision) {
		try {
			File locationsDir = new File(appContext.getFilesDir(), "locations");
			if (!locationsDir.exists()) {
				boolean created = locationsDir.mkdirs();
				if (!created) Log.w(TAG, "Failed to create locations directory: " + locationsDir.getAbsolutePath());
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
	 * Save the exploration map to internal storage using proper QiSDK serialization
	 */
	private boolean saveMapToStorage(ExplorationMap map, String mapName) {
		try {
			File mapsDir = new File(appContext.getFilesDir(), "maps");
			if (!mapsDir.exists()) {
				boolean created = mapsDir.mkdirs();
				if (!created) Log.w(TAG, "Failed to create maps directory: " + mapsDir.getAbsolutePath());
			}
			
			File mapFile = new File(mapsDir, mapName + ".map");
			
			// Check if map already exists and log warning
			if (mapFile.exists()) {
				Log.w(TAG, "Map '" + mapName + "' already exists and will be overwritten");
			}
			
					// Serialize the ExplorationMap data using StreamableBuffer to prevent OOM
		Log.i(TAG, "Serializing map data using StreamableBuffer...");
		
		StreamableBuffer streamableBuffer;
		try {
			streamableBuffer = map.serializeAsStreamableBuffer();
			Log.i(TAG, "Map serialized successfully as StreamableBuffer (size: " + streamableBuffer.getSize() + " bytes)");
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "Out of memory during map serialization. Map is too large for device memory.", e);
			return false;
		}
		
		// Write StreamableBuffer directly to file using chunked access (prevents large memory allocations)
		try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mapFile)) {
			// StreamableBuffer provides chunked access, preventing large memory allocations
			long totalSize = streamableBuffer.getSize();
			long offset = 0;
			long chunkSize = 8192; // 8KB chunks
			
			Log.i(TAG, "Writing map data in chunks (" + chunkSize + " bytes each)...");
			while (offset < totalSize) {
				long bytesToRead = Math.min(chunkSize, totalSize - offset);
				java.nio.ByteBuffer buffer = streamableBuffer.read(offset, bytesToRead);
				
				// Write buffer to file
				byte[] byteArray = new byte[buffer.remaining()];
				buffer.get(byteArray);
				fos.write(byteArray);
				
				offset += bytesToRead;
			}
			fos.flush();
			}
			
			Log.i(TAG, "Map properly serialized and saved: " + mapFile.getAbsolutePath());
			return true;
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "Out of memory while saving map. Map is too large for device memory.", e);
			return false;
		} catch (Exception e) {
			Log.e(TAG, "Failed to serialize and save map", e);
			return false;
		}
	}
	
	/**
	 * Load an exploration map from internal storage using QiSDK deserialization
	 */
	private ExplorationMap loadMapFromStorage(String mapName) {
		try {
			File mapsDir = new File(appContext.getFilesDir(), "maps");
			File mapFile = new File(mapsDir, mapName + ".map");
			
			if (!mapFile.exists()) {
				Log.i(TAG, "Map file not found: " + mapName);
				return null;
			}
			
					Log.i(TAG, "Loading map file: " + mapName + " (size: " + mapFile.length() + " bytes)");
		
		// Fallback to String-based loading with aggressive memory optimization
		// StreamableBuffer implementation failed due to QiSDK compatibility issues
		Log.i(TAG, "Using optimized String-based map loading...");
		
		long fileSize = mapFile.length();
		Log.i(TAG, "Map file size: " + (fileSize/1024/1024) + "MB (" + fileSize + " bytes)");
		
		// Aggressive memory management for large maps
		if (fileSize > 25_000_000) { // 25MB threshold
			Log.w(TAG, "Large map detected. Applying aggressive memory optimization...");
			
			// Force multiple garbage collections
			for (int i = 0; i < 3; i++) {
				System.gc();
				try {
					Thread.sleep(50);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		
		// Read map data with memory optimization
		String mapData;
		try {
			Log.i(TAG, "Reading map file with memory-optimized approach...");
			
			// Read file as bytes first (more memory efficient)
			byte[] mapBytes;
			try (FileInputStream fis = new FileInputStream(mapFile)) {
				mapBytes = new byte[(int) fileSize];
				int totalRead = 0;
				int bytesRead;
				while (totalRead < mapBytes.length && (bytesRead = fis.read(mapBytes, totalRead, mapBytes.length - totalRead)) != -1) {
					totalRead += bytesRead;
				}
			}
			
			// Convert to String in one operation (unavoidable for QiSDK)
			mapData = new String(mapBytes, StandardCharsets.UTF_8);
			
			// Release byte array immediately to help GC
			mapBytes = null;
			
			// Force GC after large allocation
			System.gc();
			
			Log.i(TAG, "Map data loaded successfully (size: " + mapData.length() + " characters)");
			
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "Out of memory loading map file. Map is too large for available memory.", e);
			throw e; // Re-throw to be caught by outer catch block
		}
		
		// Build a new ExplorationMap from the map string
			ExplorationMap explorationMap = ExplorationMapBuilder.with(qiContext)
					.withMapString(mapData)
					.build();
					
			Log.i(TAG, "Map successfully loaded from storage: " + mapName);
			return explorationMap;
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "Out of memory loading map: " + mapName + ". Map file is too large for device memory.", e);
			return null;
		} catch (Exception e) {
			Log.e(TAG, "Failed to load map from storage: " + mapName, e);
			return null;
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
	 * Clear all existing locations since they become invalid with a new map
	 * @return List of deleted location names for user feedback
	 */
	private List<String> clearAllLocations() {
		List<String> deletedLocations = new ArrayList<>();
		try {
			File locationsDir = new File(appContext.getFilesDir(), "locations");
			if (locationsDir.exists() && locationsDir.isDirectory()) {
				File[] files = locationsDir.listFiles();
				if (files != null) {
					for (File file : files) {
						if (file.getName().endsWith(".loc")) {
							String locationName = file.getName().replace(".loc", "");
							if (file.delete()) {
								deletedLocations.add(locationName);
								Log.i(TAG, "Deleted location: " + locationName);
							} else {
								Log.w(TAG, "Failed to delete location: " + locationName);
							}
						}
					}
				}
			}
			
			if (!deletedLocations.isEmpty()) {
				Log.i(TAG, "Cleared " + deletedLocations.size() + " locations for new map: " + deletedLocations);
			}
			
		} catch (Exception e) {
			Log.e(TAG, "Error clearing locations", e);
		}
		
		return deletedLocations;
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
	 * Check if the charging flap is open, which prevents movement for safety reasons
	 * @param qiContext QiContext for accessing robot services
	 * @return true if charging flap is open (movement blocked), false if closed (movement allowed)
	 */
	private boolean isChargingFlapOpen(QiContext qiContext) {
		try {
			Power power = qiContext.getPower();
			FlapSensor chargingFlap = power.getChargingFlap();
			
			if (chargingFlap != null) {
				FlapState flapState = chargingFlap.getState();
				boolean isOpen = flapState.getOpen();
				Log.d(TAG, "Charging flap status: " + (isOpen ? "OPEN (movement blocked)" : "CLOSED (movement allowed)"));
				return isOpen;
			} else {
				Log.d(TAG, "No charging flap sensor available - assuming movement is allowed");
				return false; // Assume closed if sensor not available
			}
		} catch (Exception e) {
			Log.w(TAG, "Could not check charging flap status: " + e.getMessage(), e);
			return false; // Allow movement if check fails to avoid false blocking
		}
	}
	
	/**
	 * Check if map frame is available for navigation
	 * @param qiContext QiContext for accessing robot services
	 * @return true if map frame is available, false if not
	 */
	private boolean isMapAvailable(QiContext qiContext) {
		try {
			Frame mapFrame = qiContext.getMapping().mapFrame();
			return mapFrame != null;
		} catch (Exception e) {
			Log.d(TAG, "Map frame not available: " + e.getMessage());
			return false;
		}
	}

	// Localization check helper removed for clean code

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
