package com.tqmane.wallart;

public final class CropGeometry {
    private CropGeometry() {
    }

    public static float[] clampOffset(int bitmapWidth, int bitmapHeight, int viewportWidth, int viewportHeight,
            float zoom, float offsetX, float offsetY) {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            throw new IllegalArgumentException("Crop dimensions must be positive");
        }
        float safeZoom = Float.isFinite(zoom) ? Math.max(1f, Math.min(4f, zoom)) : 1f;
        float scale = Math.max(viewportWidth / (float) bitmapWidth, viewportHeight / (float) bitmapHeight) * safeZoom;
        float maxX = Math.max(0f, (bitmapWidth * scale - viewportWidth) / 2f);
        float maxY = Math.max(0f, (bitmapHeight * scale - viewportHeight) / 2f);
        return new float[]{clamp(offsetX, -maxX, maxX), clamp(offsetY, -maxY, maxY)};
    }

    private static float clamp(float value, float min, float max) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : 0f;
    }
}
