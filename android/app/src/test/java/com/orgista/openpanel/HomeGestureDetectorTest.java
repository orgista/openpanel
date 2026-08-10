package com.orgista.openpanel;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HomeGestureDetectorTest {
    @Test
    public void recognizesStraightUpwardSwipe() {
        HomeGestureDetector detector = new HomeGestureDetector(48, 72);

        detector.onDown(120, 400);

        assertTrue(detector.onUp(132, 330));
    }

    @Test
    public void rejectsShortSwipe() {
        HomeGestureDetector detector = new HomeGestureDetector(48, 72);

        detector.onDown(120, 400);

        assertFalse(detector.onUp(120, 370));
    }

    @Test
    public void rejectsMostlyHorizontalSwipe() {
        HomeGestureDetector detector = new HomeGestureDetector(48, 72);

        detector.onDown(120, 400);

        assertFalse(detector.onUp(220, 330));
    }

    @Test
    public void cancelPreventsAStaleGesture() {
        HomeGestureDetector detector = new HomeGestureDetector(48, 72);

        detector.onDown(120, 400);
        detector.onCancel();

        assertFalse(detector.onUp(120, 300));
    }
}
