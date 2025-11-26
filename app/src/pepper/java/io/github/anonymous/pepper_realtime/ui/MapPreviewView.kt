package io.github.anonymous.pepper_realtime.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.aldebaran.qi.sdk.`object`.actuation.MapTopGraphicalRepresentation
import io.github.anonymous.pepper_realtime.data.SavedLocation
import kotlin.math.cos
import kotlin.math.sin

class MapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var locations: List<SavedLocation>? = null
    private var mapState: MapState = MapState.NO_MAP
    private var mapBitmap: Bitmap? = null
    private var mapGfx: MapTopGraphicalRepresentation? = null

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val backgroundTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }

    private val viewRect = Rect()

    fun updateData(
        locations: List<SavedLocation>?,
        state: MapState,
        mapBitmap: Bitmap?,
        mapGfx: MapTopGraphicalRepresentation?
    ) {
        this.locations = locations
        this.mapState = state
        this.mapBitmap = mapBitmap
        this.mapGfx = mapGfx
        invalidate() // Redraw the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.getClipBounds(viewRect)

        val bitmap = mapBitmap
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, viewRect, null)
        } else {
            canvas.drawColor(Color.parseColor("#555555")) // Dark background if no map
        }

        if (mapState == MapState.NO_MAP) {
            drawStatusText(canvas, "No Map Available")
            return
        }

        if (bitmap != null && mapGfx != null && locations != null) {
            drawLocations(canvas, bitmap)
        }

        val status = getStatusText()
        if (status.isNotEmpty()) {
            drawStatusText(canvas, status)
        }
    }

    private fun getStatusText(): String {
        return when (mapState) {
            MapState.LOCALIZING -> "Localizing..."
            MapState.LOCALIZATION_FAILED -> "Localization Failed"
            MapState.MAP_LOADED_NOT_LOCALIZED -> "Map Loaded"
            MapState.LOCALIZED -> {
                if (locations.isNullOrEmpty()) {
                    "Map Ready - No Locations"
                } else {
                    "" // No status text when everything is fine
                }
            }
            else -> ""
        }
    }

    private fun drawLocations(canvas: Canvas, bitmap: Bitmap) {
        val gfx = mapGfx ?: return
        val locs = locations ?: return

        for (loc in locs) {
            // Get location in map frame
            val xMap = loc.translation[0].toFloat()
            val yMap = loc.translation[1].toFloat()

            // Convert to pixel coordinates using the official formula
            val scale = gfx.scale
            val theta = gfx.theta
            val xOrigin = gfx.x
            val yOrigin = gfx.y

            val xImg = (1 / scale * (cos(theta.toDouble()).toFloat() * (xMap - xOrigin) + sin(theta.toDouble()).toFloat() * (yMap - yOrigin)))
            val yImg = (1 / scale * (sin(theta.toDouble()).toFloat() * (xMap - xOrigin) - cos(theta.toDouble()).toFloat() * (yMap - yOrigin)))

            // The image origin (0,0) is top-left, but our view might be scaled.
            // We need to map the image pixel coordinate to our view's coordinate.
            val viewX = (xImg / bitmap.width) * width
            val viewY = (yImg / bitmap.height) * height

            canvas.drawCircle(viewX, viewY, 8f, pointPaint)
            canvas.drawText(loc.name, viewX + 12, viewY + 6, textPaint)
        }
    }

    private fun drawStatusText(canvas: Canvas, text: String) {
        val x = width / 2f
        val y = height / 2f
        // Draw a semi-transparent background for the text to make it readable
        backgroundTextPaint.color = Color.argb(180, 40, 40, 40)
        canvas.drawRect(0f, y - 40, width.toFloat(), y + 15, backgroundTextPaint)
        backgroundTextPaint.color = Color.WHITE
        canvas.drawText(text, x, y, backgroundTextPaint)
    }
}

