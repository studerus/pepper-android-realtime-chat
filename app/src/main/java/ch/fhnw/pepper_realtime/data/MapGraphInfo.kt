package ch.fhnw.pepper_realtime.data

/**
 * Platform-independent representation of map graphical information.
 * Used to decouple UI from QiSDK.
 */
data class MapGraphInfo(
    val x: Float,
    val y: Float,
    val theta: Float,
    val scale: Float
)

