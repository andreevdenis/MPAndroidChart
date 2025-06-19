package com.github.mikephil.charting.data;

public class GapEntry extends Entry {
    private boolean isGap = false;
    private boolean isSinglePoint = false;

    public GapEntry(float x, float y, boolean isGap) {
        super(x, y);
        this.isGap = isGap;
    }

    public boolean isGap() {
        return isGap;
    }

    public void setGap(boolean gap) {
        isGap = gap;
    }

    public boolean isSinglePoint() {
        return isSinglePoint;
    }

    public void setSinglePoint(boolean singlePoint) {
        isSinglePoint = singlePoint;
    }
}