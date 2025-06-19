package com.github.mikephil.charting.renderer;

import com.github.mikephil.charting.data.Entry;

public class LineBuffer {
    private final float[] buffer;
    private int index = 0;        // Tracks float index
    private int sectionCount = 0; // Tracks number of (e1, e2) segments
    private final int pointsPerEntryPair;

    public LineBuffer(int entryCount, int pointsPerEntryPair) {
        this.pointsPerEntryPair = pointsPerEntryPair;
        int capacity = entryCount * pointsPerEntryPair * 4;
        this.buffer = new float[capacity];
    }

    public void add(Entry e1, Entry e2, float phaseY) {
        if (index + 4 <= buffer.length) {
            buffer[index++] = e1.getX();
            buffer[index++] = e1.getY() * phaseY;
            buffer[index++] = e2.getX();
            buffer[index++] = e2.getY() * phaseY;
            sectionCount++;
        }
    }

    public float[] getBuffer() {
        return buffer;
    }

    public int size() {
        return sectionCount;
    }

    public int floatCount() {
        return sectionCount * pointsPerEntryPair * 2;
    }

    public void reset() {
        index = 0;
        sectionCount = 0;
    }
}