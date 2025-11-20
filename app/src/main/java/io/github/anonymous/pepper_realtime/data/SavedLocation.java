package io.github.anonymous.pepper_realtime.data;

/**
 * Data class to store location data, used across tools and UI.
 * Coordinates are in the Robot Frame.
 */
@SuppressWarnings("unused") // Fields are populated via serialization/deserialization
public class SavedLocation {
    public String name;
    public String description;
    public double[] translation; // x, y, z in meters
    public double[] rotation;    // quaternion x, y, z, w
    public long timestamp;
    public boolean highPrecision;

    public SavedLocation() {}
}
