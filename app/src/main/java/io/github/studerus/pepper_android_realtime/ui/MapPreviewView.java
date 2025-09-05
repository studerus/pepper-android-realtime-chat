package io.github.studerus.pepper_android_realtime.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.aldebaran.qi.sdk.object.actuation.MapTopGraphicalRepresentation;

import java.util.List;

import io.github.studerus.pepper_android_realtime.data.SavedLocation;

public class MapPreviewView extends View {

    private List<SavedLocation> locations;
    private MapState mapState = MapState.NO_MAP;
    private Bitmap mapBitmap;
    private MapTopGraphicalRepresentation mapGfx;

    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect viewRect = new Rect();

    public MapPreviewView(Context context) {
        super(context);
        init();
    }

    public MapPreviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        pointPaint.setColor(Color.CYAN);
        pointPaint.setStyle(Paint.Style.FILL);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(16f);
        textPaint.setShadowLayer(2, 1, 1, Color.BLACK);

        backgroundTextPaint.setColor(Color.DKGRAY);
        backgroundTextPaint.setTextSize(32f);
        backgroundTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void updateData(List<SavedLocation> locations, MapState state, Bitmap mapBitmap, MapTopGraphicalRepresentation mapGfx) {
        this.locations = locations;
        this.mapState = state;
        this.mapBitmap = mapBitmap;
        this.mapGfx = mapGfx;
        invalidate(); // Redraw the view
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.getClipBounds(viewRect);

        if (mapBitmap != null) {
            canvas.drawBitmap(mapBitmap, null, viewRect, null);
        } else {
            canvas.drawColor(Color.parseColor("#555555")); // Dark background if no map
        }

        if (mapState == MapState.NO_MAP) {
            drawStatusText(canvas, "No Map Available");
            return;
        }
        
        if (mapBitmap != null && mapGfx != null && locations != null) {
            drawLocations(canvas);
        }

        String status = getStatusText();
        if (!status.isEmpty()) {
            drawStatusText(canvas, status);
        }
    }

    private String getStatusText() {
        switch (mapState) {
            case LOCALIZING: return "Localizing...";
            case LOCALIZATION_FAILED: return "Localization Failed";
            case MAP_LOADED_NOT_LOCALIZED: return "Map Loaded";
            case LOCALIZED:
                if (locations == null || locations.isEmpty()) {
                    return "Map Ready - No Locations";
                }
                return ""; // No status text when everything is fine
            default: return "";
        }
    }

    private void drawLocations(Canvas canvas) {
        for (SavedLocation loc : locations) {
            // Get location in map frame
            float xMap = (float) loc.translation[0];
            float yMap = (float) loc.translation[1];

            // Convert to pixel coordinates using the official formula
            float scale = mapGfx.getScale();
            float theta = mapGfx.getTheta();
            float x_origin = mapGfx.getX();
            float y_origin = mapGfx.getY();

            float x_img = (1 / scale * ((float)Math.cos(theta) * (xMap - x_origin) + (float)Math.sin(theta) * (yMap - y_origin)));
            float y_img = (1 / scale * ((float)Math.sin(theta) * (xMap - x_origin) - (float)Math.cos(theta) * (yMap - y_origin)));

            // The image origin (0,0) is top-left, but our view might be scaled.
            // We need to map the image pixel coordinate to our view's coordinate.
            float viewX = (x_img / mapBitmap.getWidth()) * getWidth();
            float viewY = (y_img / mapBitmap.getHeight()) * getHeight();
            
            canvas.drawCircle(viewX, viewY, 8f, pointPaint);
            canvas.drawText(loc.name, viewX + 12, viewY + 6, textPaint);
        }
    }

    private void drawStatusText(Canvas canvas, String text) {
        float x = getWidth() / 2f;
        float y = getHeight() / 2f;
        // Draw a semi-transparent background for the text to make it readable
        backgroundTextPaint.setColor(Color.argb(180, 40, 40, 40));
        canvas.drawRect(0, y - 40, getWidth(), y + 15, backgroundTextPaint);
        backgroundTextPaint.setColor(Color.WHITE);
        canvas.drawText(text, x, y, backgroundTextPaint);
    }
}
