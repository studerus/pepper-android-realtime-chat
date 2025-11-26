package io.github.anonymous.pepper_realtime.data

/**
 * Data class to store location data, used across tools and UI.
 * Coordinates are in the Robot Frame.
 */
data class SavedLocation(
    @JvmField var name: String = "",
    @JvmField var description: String = "",
    @JvmField var translation: DoubleArray = doubleArrayOf(), // x, y, z in meters
    @JvmField var rotation: DoubleArray = doubleArrayOf(),    // quaternion x, y, z, w
    @JvmField var timestamp: Long = 0L,
    @JvmField var highPrecision: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SavedLocation

        if (name != other.name) return false
        if (description != other.description) return false
        if (!translation.contentEquals(other.translation)) return false
        if (!rotation.contentEquals(other.rotation)) return false
        if (timestamp != other.timestamp) return false
        if (highPrecision != other.highPrecision) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + translation.contentHashCode()
        result = 31 * result + rotation.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + highPrecision.hashCode()
        return result
    }
}

