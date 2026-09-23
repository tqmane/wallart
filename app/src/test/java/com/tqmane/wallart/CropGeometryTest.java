package com.tqmane.wallart;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CropGeometryTest {
    @Test
    public void clampsPanningToImageEdges() {
        float[] wide = CropGeometry.clampOffset(2000, 1000, 700, 440, 1f, 200f, 30f);
        assertEquals(90f, wide[0], 0.01f);
        assertEquals(0f, wide[1], 0.01f);

        float[] tall = CropGeometry.clampOffset(1000, 2000, 700, 440, 2f, -900f, 900f);
        assertEquals(-350f, tall[0], 0.01f);
        assertEquals(900f, tall[1], 0.01f);
    }
}
