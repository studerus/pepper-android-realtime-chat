package io.github.studerus.pepper_android_realtime.tools.navigation;

import android.util.Log;
import com.aldebaran.qi.sdk.builder.TransformBuilder;
import com.aldebaran.qi.sdk.object.actuation.Frame;
import com.aldebaran.qi.sdk.object.geometry.Transform;
import io.github.studerus.pepper_android_realtime.tools.Tool;
import io.github.studerus.pepper_android_realtime.tools.ToolContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Tool for saving robot's current location for future navigation.
 * Captures current position and orientation with optional description.
 */
public class SaveCurrentLocationTool implements Tool {
    
    private static final String TAG = "SaveCurrentLocationTool";

    @Override
    public String getName() {
        return "save_current_location";
    }

    @Override
    public JSONObject getDefinition() {
        try {
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", getName());
            tool.put("description", "Save Pepper's current position with a name for future navigation. Use this when the user wants to save a location like 'kitchen', 'printer', 'entrance', etc. Call the function directly without announcing it.");
            
            JSONObject params = new JSONObject();
            params.put("type", "object");
            
            JSONObject properties = new JSONObject();
            properties.put("location_name", new JSONObject()
                .put("type", "string")
                .put("description", "Name for this location (e.g. 'kitchen', 'printer', 'entrance')"));
            properties.put("description", new JSONObject()
                .put("type", "string")
                .put("description", "Optional description of this location")
                .put("default", ""));
            
            params.put("properties", properties);
            params.put("required", new JSONArray().put("location_name"));
            tool.put("parameters", params);
            
            return tool;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create tool definition", e);
        }
    }

    @Override
    public String execute(JSONObject args, ToolContext context) throws Exception {
        String locationName = args.optString("location_name", "");
        String description = args.optString("description", "");
        
        // Validate location name
        if (locationName.trim().isEmpty()) {
            return new JSONObject().put("error", "Location name is required").toString();
        }
        
        // Check if robot is ready
        if (context.isQiContextNotReady()) {
            return new JSONObject().put("error", "Robot not ready").toString();
        }
        
        Log.i(TAG, "Saving current location: " + locationName);
        
        try {
            Transform currentTransform;
            // Get current robot position
            // Note: Currently using standard precision - high-precision mapping detection
            // from original ToolExecutor would need to be implemented here for full functionality
            Log.i(TAG, "📍 Standard location save: Using current robot frame");
            Frame robotFrame = context.getQiContext().getActuation().robotFrame();
            try {
                Frame mapFrame = context.getQiContext().getMapping().mapFrame();
                currentTransform = robotFrame.computeTransform(mapFrame).getTransform();
            } catch (Exception e) {
                Log.w(TAG, "No map frame available, using identity transform: " + e.getMessage());
                // Create identity transform if no map is available
                currentTransform = TransformBuilder.create().fromXTranslation(0.0);
            }
            
            // Save location data
            boolean saved = saveLocationToStorage(context, locationName, description, currentTransform);
            
            if (saved) {
                // IMPORTANT: Refresh the location provider to update the cache
                if (context.getLocationProvider() != null) {
                    context.getLocationProvider().refreshLocations(context.getAppContext());
                }

                JSONObject result = new JSONObject();
                result.put("status", "Location saved successfully");
                result.put("location_name", locationName);
                result.put("high_precision", false); // Currently standard precision only
                if (!description.isEmpty()) {
                    result.put("description", description);
                }
                
                String precisionNote = ""; // Currently standard precision only
                result.put("message", String.format(Locale.US, 
                    "Location '%s' has been successfully saved%s%s. Navigation to this location is now available.", 
                    locationName, description.isEmpty() ? "" : " (" + description + ")", precisionNote));
                Log.i(TAG, "Location '" + locationName + "' saved successfully (standard precision)");
                return result.toString();
            } else {
                return new JSONObject().put("error", "Location could not be saved to storage").toString();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving location", e);
            return new JSONObject().put("error", "Failed to save location: " + e.getMessage()).toString();
        }
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
     * Save a location to internal storage
     */
    private boolean saveLocationToStorage(ToolContext context, String locationName, String description, Transform transform) {
        try {
            File locationsDir = new File(context.getAppContext().getFilesDir(), "locations");
            if (!locationsDir.exists()) {
                boolean created = locationsDir.mkdirs();
                if (!created) Log.w(TAG, "Failed to create locations directory: " + locationsDir.getAbsolutePath());
            }
            
            File locationFile = new File(locationsDir, locationName + ".loc");
            
            // Check if location already exists and log warning
            if (locationFile.exists()) {
                Log.w(TAG, "Location '" + locationName + "' already exists and will be overwritten");
            }
            
            // Save as JSON for easier debugging and cross-platform compatibility
            JSONObject locationData = new JSONObject();
            locationData.put("name", locationName);
            locationData.put("description", description);
            
            // Store transform data
            if (transform != null) {
                JSONArray translation = new JSONArray();
                translation.put(transform.getTranslation().getX());
                translation.put(transform.getTranslation().getY());
                translation.put(transform.getTranslation().getZ());
                locationData.put("translation", translation);
                
                JSONArray rotation = new JSONArray();
                rotation.put(transform.getRotation().getX());
                rotation.put(transform.getRotation().getY());
                rotation.put(transform.getRotation().getZ());
                rotation.put(transform.getRotation().getW());
                locationData.put("rotation", rotation);
            } else {
                // Default values if transform is null
                locationData.put("translation", new JSONArray().put(0).put(0).put(0));
                locationData.put("rotation", new JSONArray().put(0).put(0).put(0).put(1));
            }
            
            locationData.put("timestamp", System.currentTimeMillis());
            locationData.put("high_precision", false); // Currently standard precision only
            
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
}
