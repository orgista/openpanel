package com.orgista.openpanel;

/** Small state machine for the opt-in bottom-edge Home gesture. */
final class HomeGestureDetector {
    private final float minimumUpwardDistance;
    private final float maximumHorizontalDrift;
    private float downX;
    private float downY;
    private boolean tracking;

    HomeGestureDetector(float minimumUpwardDistance, float maximumHorizontalDrift) {
        this.minimumUpwardDistance = minimumUpwardDistance;
        this.maximumHorizontalDrift = maximumHorizontalDrift;
    }

    void onDown(float x, float y) {
        downX = x;
        downY = y;
        tracking = true;
    }

    boolean onUp(float x, float y) {
        if (!tracking) return false;
        tracking = false;
        float upwardDistance = downY - y;
        float horizontalDrift = Math.abs(x - downX);
        return upwardDistance >= minimumUpwardDistance
            && horizontalDrift <= maximumHorizontalDrift;
    }

    void onCancel() {
        tracking = false;
    }
}
