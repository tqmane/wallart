package com.tqmane.wallart;

import android.widget.ImageView;

public final class FitMode {
    public static final String FIT_CENTER = "FIT_CENTER";
    public static final String CENTER_CROP = "CENTER_CROP";
    public static final String CENTER_INSIDE = "CENTER_INSIDE";

    private FitMode() {
    }

    public static boolean isValid(String value) {
        return FIT_CENTER.equals(value) || CENTER_CROP.equals(value) || CENTER_INSIDE.equals(value);
    }

    public static ImageView.ScaleType scaleType(String value) {
        if (CENTER_CROP.equals(value)) return ImageView.ScaleType.CENTER_CROP;
        if (CENTER_INSIDE.equals(value)) return ImageView.ScaleType.CENTER_INSIDE;
        return ImageView.ScaleType.FIT_CENTER;
    }
}
