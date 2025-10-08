package io.github.anonymous.pepper_realtime.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides a cached, thread-safe list of saved locations from disk.
 */
public class LocationProvider {
    private static final String TAG = "LocationProvider";
    private List<SavedLocation> cachedLocations = new ArrayList<>();

    public List<SavedLocation> getSavedLocations() {
        return new ArrayList<>(cachedLocations); // Return a copy for safety
    }

    /**
     * Refreshes the location cache from disk on a background thread.
     */
    public void refreshLocations(Context context) {
        new Thread(() -> {
            List<SavedLocation> locations = new ArrayList<>();
            if (context == null) {
                Log.w(TAG, "Context is null, cannot refresh locations.");
                return;
            }

            try {
                File locationsDir = new File(context.getFilesDir(), "locations");
                if (!locationsDir.exists() || !locationsDir.isDirectory()) {
                    Log.d(TAG, "Locations directory does not exist.");
                    synchronized (this) {
                        cachedLocations = locations;
                    }
                    return;
                }

                File[] locationFiles = locationsDir.listFiles((dir, name) -> name.endsWith(".loc"));
                if (locationFiles != null) {
                    for (File file : locationFiles) {
                        SavedLocation loc = loadLocationFromFile(file);
                        if (loc != null) {
                            locations.add(loc);
                        }
                    }
                }
                Collections.sort(locations, (l1, l2) -> l1.name.compareToIgnoreCase(l2.name));
                synchronized (this) {
                    cachedLocations = locations;
                }
                Log.i(TAG, "Refreshed and found " + cachedLocations.size() + " locations.");
            } catch (Exception e) {
                Log.e(TAG, "Error refreshing locations", e);
            }
        }, "location-provider-thread").start();
    }

    private SavedLocation loadLocationFromFile(File locationFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(locationFile), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }

            JSONObject data = new JSONObject(content.toString());
            SavedLocation loc = new SavedLocation();
            loc.name = data.optString("name", locationFile.getName().replace(".loc", ""));
            loc.description = data.optString("description", "");
            loc.timestamp = data.optLong("timestamp", 0);
            loc.highPrecision = data.optBoolean("high_precision", false);

            JSONArray transArr = data.getJSONArray("translation");
            loc.translation = new double[]{ transArr.getDouble(0), transArr.getDouble(1), transArr.getDouble(2) };

            JSONArray rotArr = data.getJSONArray("rotation");
            loc.rotation = new double[]{ rotArr.getDouble(0), rotArr.getDouble(1), rotArr.getDouble(2), rotArr.getDouble(3) };
            
            return loc;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load location from " + locationFile.getName(), e);
            return null;
        }
    }
}
