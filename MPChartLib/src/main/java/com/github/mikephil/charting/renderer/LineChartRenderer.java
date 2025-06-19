package com.github.mikephil.charting.renderer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.util.Log;
import androidx.annotation.Nullable;
import com.github.mikephil.charting.animation.ChartAnimator;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.GapEntry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;
import com.github.mikephil.charting.interfaces.datasets.IDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.MPPointD;
import com.github.mikephil.charting.utils.MPPointF;
import com.github.mikephil.charting.utils.Transformer;
import com.github.mikephil.charting.utils.Utils;
import com.github.mikephil.charting.utils.ViewPortHandler;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class LineChartRenderer extends LineRadarRenderer {

    protected LineDataProvider mChart;

    /**
     * paint for the inner circle of the value indicators
     */
    protected Paint mCirclePaintInner;

    /**
     * Bitmap object used for drawing the paths (otherwise they are too long if
     * rendered directly on the canvas)
     */
    protected WeakReference<Bitmap> mDrawBitmap;

    /**
     * on this canvas, the paths are rendered, it is initialized with the
     * pathBitmap
     */
    protected Canvas mBitmapCanvas;

    /**
     * the bitmap configuration to be used
     */
    protected Bitmap.Config mBitmapConfig = Bitmap.Config.ARGB_8888;

    protected Path cubicPath = new Path();
    protected Path cubicFillPath = new Path();

    public LineChartRenderer(LineDataProvider chart, ChartAnimator animator,
                             ViewPortHandler viewPortHandler) {
        super(animator, viewPortHandler);
        mChart = chart;

        mCirclePaintInner = new Paint(Paint.ANTI_ALIAS_FLAG);
        mCirclePaintInner.setStyle(Paint.Style.FILL);
        mCirclePaintInner.setColor(Color.WHITE);
    }

    @Override
    public void initBuffers() {
    }

    @Override
    public void drawData(Canvas c) {

        int width = (int) mViewPortHandler.getChartWidth();
        int height = (int) mViewPortHandler.getChartHeight();

        Bitmap drawBitmap = mDrawBitmap == null ? null : mDrawBitmap.get();

        if (drawBitmap == null
                || (drawBitmap.getWidth() != width)
                || (drawBitmap.getHeight() != height)) {

            if (width > 0 && height > 0) {
                drawBitmap = Bitmap.createBitmap(width, height, mBitmapConfig);
                mDrawBitmap = new WeakReference<>(drawBitmap);
                mBitmapCanvas = new Canvas(drawBitmap);
            } else
                return;
        }

        drawBitmap.eraseColor(Color.TRANSPARENT);

        LineData lineData = mChart.getLineData();

        for (ILineDataSet set : lineData.getDataSets()) {

            if (set.isVisible())
                drawDataSet(c, set);
        }

        c.drawBitmap(drawBitmap, 0, 0, mRenderPaint);
    }

    protected void drawDataSet(Canvas c, ILineDataSet dataSet) {

        if (dataSet.getEntryCount() < 1)
            return;

        mRenderPaint.setStrokeWidth(dataSet.getLineWidth());
        mRenderPaint.setPathEffect(dataSet.getDashPathEffect());

        switch (dataSet.getMode()) {
            default:
            case LINEAR:
            case STEPPED:
                drawLinear(c, dataSet);
                break;

            case CUBIC_BEZIER:
                drawCubicBezier(dataSet);
                break;

            case HORIZONTAL_BEZIER:
                drawHorizontalBezier(dataSet);
                break;
        }

        mRenderPaint.setPathEffect(null);
    }

    protected void drawHorizontalBezier(ILineDataSet dataSet) {

        float phaseY = mAnimator.getPhaseY();

        Transformer trans = mChart.getTransformer(dataSet.getAxisDependency());

        mXBounds.set(mChart, dataSet);

        cubicPath.reset();

        if (mXBounds.range >= 1) {

            Entry prev = dataSet.getEntryForIndex(mXBounds.min);
            Entry cur = prev;

            // let the spline start
            cubicPath.moveTo(cur.getX(), cur.getY() * phaseY);

            for (int j = mXBounds.min + 1; j <= mXBounds.range + mXBounds.min; j++) {

                prev = cur;
                cur = dataSet.getEntryForIndex(j);

                final float cpx = (prev.getX())
                        + (cur.getX() - prev.getX()) / 2.0f;

                cubicPath.cubicTo(
                        cpx, prev.getY() * phaseY,
                        cpx, cur.getY() * phaseY,
                        cur.getX(), cur.getY() * phaseY);
            }
        }

        // if filled is enabled, close the path
        if (dataSet.isDrawFilledEnabled()) {

            cubicFillPath.reset();
            cubicFillPath.addPath(cubicPath);
            // create a new path, this is bad for performance
            drawCubicFill(mBitmapCanvas, dataSet, cubicFillPath, trans, mXBounds);
        }

        mRenderPaint.setColor(dataSet.getColor());

        mRenderPaint.setStyle(Paint.Style.STROKE);

        trans.pathValueToPixel(cubicPath);

        mBitmapCanvas.drawPath(cubicPath, mRenderPaint);

        mRenderPaint.setPathEffect(null);
    }

    protected void drawCubicBezier(ILineDataSet dataSet) {

        float phaseY = mAnimator.getPhaseY();

        Transformer trans = mChart.getTransformer(dataSet.getAxisDependency());

        mXBounds.set(mChart, dataSet);

        float intensity = dataSet.getCubicIntensity();

        cubicPath.reset();

        if (mXBounds.range >= 1) {

            float prevDx = 0f;
            float prevDy = 0f;
            float curDx = 0f;
            float curDy = 0f;

            // Take an extra point from the left, and an extra from the right.
            // That's because we need 4 points for a cubic bezier (cubic=4), otherwise we get lines moving and doing weird stuff on the edges of the chart.
            // So in the starting `prev` and `cur`, go -2, -1
            // And in the `lastIndex`, add +1

            final int firstIndex = mXBounds.min + 1;
            final int lastIndex = mXBounds.min + mXBounds.range;

            Entry prevPrev;
            Entry prev = dataSet.getEntryForIndex(Math.max(firstIndex - 2, 0));
            Entry cur = dataSet.getEntryForIndex(Math.max(firstIndex - 1, 0));
            Entry next = cur;
            int nextIndex = -1;

            if (cur == null) return;

            // let the spline start
            cubicPath.moveTo(cur.getX(), cur.getY() * phaseY);

            for (int j = mXBounds.min + 1; j <= mXBounds.range + mXBounds.min; j++) {

                prevPrev = prev;
                prev = cur;
                cur = nextIndex == j ? next : dataSet.getEntryForIndex(j);

                nextIndex = j + 1 < dataSet.getEntryCount() ? j + 1 : j;
                next = dataSet.getEntryForIndex(nextIndex);

                prevDx = (cur.getX() - prevPrev.getX()) * intensity;
                prevDy = (cur.getY() - prevPrev.getY()) * intensity;
                curDx = (next.getX() - prev.getX()) * intensity;
                curDy = (next.getY() - prev.getY()) * intensity;

                cubicPath.cubicTo(prev.getX() + prevDx, (prev.getY() + prevDy) * phaseY,
                        cur.getX() - curDx,
                        (cur.getY() - curDy) * phaseY, cur.getX(), cur.getY() * phaseY);
            }
        }

        // if filled is enabled, close the path
        if (dataSet.isDrawFilledEnabled()) {

            cubicFillPath.reset();
            cubicFillPath.addPath(cubicPath);

            drawCubicFill(mBitmapCanvas, dataSet, cubicFillPath, trans, mXBounds);
        }

        mRenderPaint.setColor(dataSet.getColor());

        mRenderPaint.setStyle(Paint.Style.STROKE);

        trans.pathValueToPixel(cubicPath);

        mBitmapCanvas.drawPath(cubicPath, mRenderPaint);

        mRenderPaint.setPathEffect(null);
    }

    protected void drawCubicFill(Canvas c, ILineDataSet dataSet, Path spline, Transformer trans, XBounds bounds) {

        float fillMin = dataSet.getFillFormatter()
                .getFillLinePosition(dataSet, mChart);

        spline.lineTo(dataSet.getEntryForIndex(bounds.min + bounds.range).getX(), fillMin);
        spline.lineTo(dataSet.getEntryForIndex(bounds.min).getX(), fillMin);
        spline.close();

        trans.pathValueToPixel(spline);

        final Drawable drawable = dataSet.getFillDrawable();
        if (drawable != null) {

            drawFilledPath(c, spline, drawable);
        } else {

            drawFilledPath(c, spline, dataSet.getFillColor(), dataSet.getFillAlpha());
        }
    }

    private float[] mLineBuffer = new float[4];

    /**
     * Draws a normal line.
     *
     * @param canvas
     * @param dataSet
     */
    protected void drawLinear(Canvas canvas, ILineDataSet dataSet) {
        final int pointsPerEntryPair = 2;
        final float maximumGapBetweenPoints = dataSet.getMaximumGapBetweenPoints();

        YAxis axisLeft = mChart.getAxis(YAxis.AxisDependency.LEFT);
        List<LimitLine> limitLines = axisLeft.getLimitLines();
        Float topLimitLine = null;
        Float bottomLimitLine = null;
        for (LimitLine line : limitLines) {
            float limit = line.getLimit();
            if (topLimitLine == null || limit > topLimitLine) {
                topLimitLine = limit;
            }
            if (bottomLimitLine == null || limit < bottomLimitLine) {
                bottomLimitLine = limit;
            }
        }
        boolean limitsEnabled = topLimitLine != null && bottomLimitLine != null;

        ILineDataSet dataEntries = buildLineDataSetWithIntersections(dataSet, topLimitLine, bottomLimitLine, maximumGapBetweenPoints);

        int entryCount = dataEntries.getEntryCount();

        Transformer trans = mChart.getTransformer(dataEntries.getAxisDependency());

        float phaseY = mAnimator.getPhaseY();

        mRenderPaint.setStyle(Paint.Style.STROKE);

        mXBounds.set(mChart, dataEntries);

        // if drawing filled is enabled
        if (dataEntries.isDrawFilledEnabled() && entryCount > 0) {
            drawLinearFill(canvas, dataEntries, trans, mXBounds);
        }

        LineBuffer lineBuffer = new LineBuffer(entryCount, pointsPerEntryPair);
        LineBuffer lineLimitBuffer = new LineBuffer(entryCount, pointsPerEntryPair);
        LineBuffer dottedBuffer = new LineBuffer(entryCount, pointsPerEntryPair);
        LineBuffer dottedLimitBuffer = new LineBuffer(entryCount, pointsPerEntryPair);

        GapEntry e1, e2;

        e1 = (GapEntry) dataEntries.getEntryForIndex(mXBounds.min);

        if (e1 != null) {
            List<Entry> drawDotsList = new ArrayList<>();
            if (e1.isSinglePoint()) {
                drawDotsList.add(e1);
            }

            for (int x = mXBounds.min + 1; x <= mXBounds.range + mXBounds.min; x++) {
                e2 = (GapEntry) dataEntries.getEntryForIndex(x);
                if (e2 == null) continue;


                float y1 = e1.getY();
                float y2 = e2.getY();

                if (limitsEnabled) {
                    if (y1 > topLimitLine || y1 < bottomLimitLine || y2 > topLimitLine || y2 < bottomLimitLine) {
                        if (e2.isGap()) {
                            dottedLimitBuffer.add(e1, e2, phaseY);
                        } else {
                            lineLimitBuffer.add(e1, e2, phaseY);
                        }
                    } else {
                        if (e2.isGap()) {
                            dottedBuffer.add(e1, e2, phaseY);
                        } else {
                            lineBuffer.add(e1, e2, phaseY);
                        }
                    }
                } else {
                    if (e2.isGap()) {
                        dottedBuffer.add(e1, e2, phaseY);
                    } else {
                        lineBuffer.add(e1, e2, phaseY);
                    }
                }

                if (e2.isGap()) {
                    drawDotsList.add(e2);
                }

                e1 = e2;
            }

            if (lineBuffer.size() > 0) {
                mRenderPaint.setColor(dataEntries.getColor());
                trans.pointValuesToPixel(lineBuffer.getBuffer());
                canvas.drawLines(lineBuffer.getBuffer(), 0, lineBuffer.floatCount(), mRenderPaint);
            }

            if (dottedBuffer.size() > 0) {
                mDottedPaint.setColor(dataEntries.getColor());
                trans.pointValuesToPixel(dottedBuffer.getBuffer());
                canvas.drawLines(dottedBuffer.getBuffer(), 0, dottedBuffer.floatCount(), mDottedPaint);
            }

            if (lineLimitBuffer.size() > 0) {
                mRenderPaint.setColor(limitLines.get(0).getLineColor());
                trans.pointValuesToPixel(lineLimitBuffer.getBuffer());
                canvas.drawLines(lineLimitBuffer.getBuffer(), 0, lineLimitBuffer.floatCount(), mRenderPaint);
            }

            if (dottedLimitBuffer.size() > 0) {
                mDottedPaint.setColor(limitLines.get(0).getLineColor());
                trans.pointValuesToPixel(dottedLimitBuffer.getBuffer());
                canvas.drawLines(dottedLimitBuffer.getBuffer(), 0, dottedLimitBuffer.floatCount(), mDottedPaint);
            }

            if (!drawDotsList.isEmpty()) {
                float[] pointToDraw = new float[2];
                for (Entry entry : drawDotsList) {
                    pointToDraw[0] = entry.getX();
                    pointToDraw[1] = entry.getY();
                    trans.pointValuesToPixel(pointToDraw);
                    if (limitsEnabled && (entry.getY() >= topLimitLine || entry.getY() <= bottomLimitLine)) {
                        mRenderPaint.setColor(limitLines.get(0).getLineColor());
                    } else {
                        mRenderPaint.setColor(dataEntries.getColor());
                    }
                    canvas.drawCircle(pointToDraw[0], pointToDraw[1], 0.7f, mRenderPaint);
                }
            }
        }

        mRenderPaint.setPathEffect(null);
    }

    public ILineDataSet buildLineDataSetWithIntersections(
            ILineDataSet original,
            @Nullable Float topLimitLine,
            @Nullable Float bottomLimitLine,
            float maximumGapBetweenPoints
    ) {
        List<GapEntry> resultEntries = new ArrayList<>();
        int entryCount = original.getEntryCount();

        if (entryCount < 1) return new LineDataSet(new ArrayList<>(resultEntries), original.getLabel());
        boolean useGaps = maximumGapBetweenPoints > 0;

        Entry firstPoint = original.getEntryForIndex(0);
        float x1 = firstPoint.getX();
        float y1 = firstPoint.getY();
        GapEntry e1 = new GapEntry(x1, y1, true);
        GapEntry e2 = null;
        resultEntries.add(e1);

        for (int i = 1; i < entryCount; i++) {
            Entry currentPoint = original.getEntryForIndex(i);
            float x2 = currentPoint.getX();
            float y2 = currentPoint.getY();

            boolean isGap = useGaps && (x2 - x1 > maximumGapBetweenPoints);
            e2 = new GapEntry(x2, y2, isGap);

            if (isGap && e1.isGap()) {
                e1.setSinglePoint(true);
            }

            // Interpolate for top limit
            GapEntry newEntry1 = null;
            GapEntry newEntry2 = null;

            if (topLimitLine != null && crossesLimit(y1, y2, topLimitLine)) {
                newEntry1 = interpolateGapEntry(x1, y1, x2, y2, topLimitLine, isGap);
            }

            // Interpolate for bottom limit
            if (bottomLimitLine != null && crossesLimit(y1, y2, bottomLimitLine)) {
                newEntry2 = interpolateGapEntry(x1, y1, x2, y2, bottomLimitLine, isGap);
            }

            if (newEntry1 != null && newEntry2 != null) {
                if (newEntry1.getX() < newEntry2.getX()) {
                    resultEntries.add(newEntry1);
                    resultEntries.add(newEntry2);
                } else {
                    resultEntries.add(newEntry2);
                    resultEntries.add(newEntry1);
                }
            } else {
                if (newEntry1 != null) resultEntries.add(newEntry1);
                if (newEntry2 != null) resultEntries.add(newEntry2);
            }

            resultEntries.add(e2);
            x1 = x2;
            y1 = y2;
            e1 = e2;
        }

        if (e2 != null && e2.isGap()) {
            e2.setSinglePoint(true);
        }
        // Create a new dataset with the same styling
        LineDataSet newDataSet = new LineDataSet(new ArrayList<>(resultEntries), original.getLabel());

        // Optional: copy styling from original
        copyStyle(original, newDataSet);

        return newDataSet;
    }

    private static void copyStyle(ILineDataSet from, LineDataSet to) {
        to.setColor(from.getColor());
        to.setLineWidth(from.getLineWidth());
        to.setDrawCircles(from.isDrawCirclesEnabled());
        to.setCircleRadius(from.getCircleRadius());
        to.setCircleHoleRadius(from.getCircleHoleRadius());
        to.setCircleHoleColor(from.getCircleHoleColor());
        to.setDrawCircleHole(from.isDrawCircleHoleEnabled());
        to.setDrawValues(from.isDrawValuesEnabled());
        to.setMode(from.getMode());
        to.setHighlightEnabled(from.isHighlightEnabled());
        to.setDrawFilled(from.isDrawFilledEnabled());
        to.setFillColor(from.getFillColor());
        to.setFillAlpha(from.getFillAlpha());
        to.setHighLightColor(from.getHighLightColor());
    }

    private static boolean crossesLimit(float y1, float y2, float limit) {
        return (y1 < limit && y2 > limit) || (y1 > limit && y2 < limit);
    }

    private static GapEntry interpolateGapEntry(float x1, float y1, float x2, float y2, float limitY, boolean isGap) {
        if (y1 == y2) return new GapEntry((x1 + x2) / 2f, limitY, isGap);
        float t = (limitY - y1) / (y2 - y1);
        float interpolatedX = x1 + t * (x2 - x1);
        return new GapEntry(interpolatedX, limitY, isGap);
    }

    protected Path mGenerateFilledPathBuffer = new Path();
    protected Path mGenerateLimitTopPathBuffer = new Path();
    protected Path mGenerateLimitBottomPathBuffer = new Path();

    /**
     * Draws a filled linear path on the canvas.
     *
     * @param canvas
     * @param dataSet
     * @param trans
     * @param bounds
     */
    protected void drawLinearFill(Canvas canvas, ILineDataSet dataSet, Transformer trans, XBounds bounds) {
        YAxis axisLeft = mChart.getAxis(YAxis.AxisDependency.LEFT);
        float topY = axisLeft.getAxisMaximum();
        float bottomY = axisLeft.getAxisMinimum();
        List<LimitLine> limitLines = axisLeft.getLimitLines();
        Float topLimitLine = null;
        Float bottomLimitLine = null;
        Integer limitColor = null;
        for (LimitLine line : limitLines) {
            limitColor = line.getLineColor();
            float limit = line.getLimit();
            if (topLimitLine == null || limit > topLimitLine) {
                topLimitLine = limit;
            }
            if (bottomLimitLine == null || limit < bottomLimitLine) {
                bottomLimitLine = limit;
            }
        }
        boolean limitsEnabled = topLimitLine != null && bottomLimitLine != null;
        boolean topLimitVisible = topLimitLine != null && topLimitLine < topY;
        boolean bottomLimitVisible = bottomLimitLine != null && bottomLimitLine > bottomY;
        boolean fillVisible = limitsEnabled && (topY >= bottomLimitLine && bottomY <= topLimitLine);

        final Path filled = mGenerateFilledPathBuffer;
        final Path topLimitFill = mGenerateLimitTopPathBuffer;
        final Path bottomLimitFill = mGenerateLimitBottomPathBuffer;

        final float phaseY = mAnimator.getPhaseY();
        final int startingIndex = bounds.min;
        final int endingIndex = bounds.range + bounds.min;
        final int indexInterval = 128;

        int currentStartIndex = 0;
        int currentEndIndex = 0;
        GapEntry e1, e2;
        boolean breakInData = false;
        final float fillMin = dataSet.getFillFormatter().getFillLinePosition(dataSet, mChart);


        // Doing this iteratively in order to avoid OutOfMemory errors that can happen on large bounds sets.
        // Denis: also to process gaps in dataSet

        List<GapEntry> singlePoints = new ArrayList<>();

        for (int index = startingIndex; index <= endingIndex; index++) {
            //e1 = (GapEntry) dataSet.getEntryForIndex(index == 0 ? 0 : (index - 1));
            e1 = (GapEntry) dataSet.getEntryForIndex(index);

            breakInData = e1.isGap();
            if (e1.isSinglePoint()) {
                Log.d("LineChartRenderer", "isSinglePoint "+e1.isGap()+ " _ " + e1.getY());
            }

            if (breakInData || index - currentStartIndex >= indexInterval || index == endingIndex) {

                currentEndIndex = breakInData ? Integer.max(index - 1, 0) : index;

                if (currentStartIndex != currentEndIndex) {
                    if (limitsEnabled) {
                        if (fillVisible) {
                            generateFilledForLimitsPath(dataSet, currentStartIndex, currentEndIndex, topLimitLine, bottomLimitLine, filled);
                            trans.pathValueToPixel(filled);
                            drawFilledPath(canvas, filled, dataSet.getFillColor(), dataSet.getFillAlpha());
                        }
                        if (topLimitVisible) {
                            generateTopLimitsPath(dataSet, currentStartIndex, currentEndIndex, topLimitLine, topLimitFill);
                            trans.pathValueToPixel(topLimitFill);
                            drawFilledPath(canvas, topLimitFill, limitColor, dataSet.getFillAlpha());
                        }
                        if (bottomLimitVisible) {
                            generateBottomLimitsPath(dataSet, currentStartIndex, currentEndIndex, bottomLimitLine, bottomLimitFill);
                            trans.pathValueToPixel(bottomLimitFill);
                            drawFilledPath(canvas, bottomLimitFill, limitColor, dataSet.getFillAlpha());
                        }
                    } else {
                        generateFilledPath(dataSet, currentStartIndex, currentEndIndex, filled);
                        trans.pathValueToPixel(filled);
                        drawFilledPath(canvas, filled, dataSet.getFillColor(), dataSet.getFillAlpha());
                    }
                }

                if (e1.isSinglePoint()) {
                    float x = e1.getX();
                    float y = e1.getY();
                    float[] linePoint1 = new float[2];
                    float[] linePoint2 = new float[2];
                    if (limitsEnabled) {
                        if (topLimitVisible) {
                            final float limitFillLine = topLimitLine > fillMin ? topLimitLine : fillMin;
                            linePoint1[0] = x;
                            linePoint1[1] = limitFillLine;
                            linePoint2[0] = x;
                            linePoint2[1] = Float.max(limitFillLine, y) * phaseY;
                            trans.pointValuesToPixel(linePoint1);
                            trans.pointValuesToPixel(linePoint2);
                            drawLine(canvas, linePoint1[0], linePoint1[1], linePoint2[0], linePoint2[1], limitColor, dataSet.getFillAlpha());
                        }
                        if (bottomLimitVisible) {
                            final float limitFillLine = bottomLimitLine > fillMin ? fillMin : bottomLimitLine;
                            linePoint1[0] = x;
                            linePoint1[1] = limitFillLine;
                            linePoint2[0] = x;
                            linePoint2[1] = Float.min(bottomLimitLine, y) * phaseY;
                            trans.pointValuesToPixel(linePoint1);
                            trans.pointValuesToPixel(linePoint2);
                            drawLine(canvas, linePoint1[0], linePoint1[1], linePoint2[0], linePoint2[1], limitColor, dataSet.getFillAlpha());
                        }
                        if (fillVisible) {
                            final float limitFillLine = topLimitLine >= fillMin ? Float.max(bottomLimitLine, fillMin) : topLimitLine;
                            if (y > topLimitLine) {
                                y = topLimitLine;
                            } else if (y < bottomLimitLine) {
                                y = bottomLimitLine;
                            }
                            linePoint1[0] = x;
                            linePoint1[1] = limitFillLine;
                            linePoint2[0] = x;
                            linePoint2[1] = Float.max(limitFillLine, y) * phaseY;
                            trans.pointValuesToPixel(linePoint1);
                            trans.pointValuesToPixel(linePoint2);
                            drawLine(canvas, linePoint1[0], linePoint1[1], linePoint2[0], linePoint2[1], dataSet.getFillColor(), dataSet.getFillAlpha());
                        }
                    } else {
                        linePoint1[0] = x;
                        linePoint1[1] = fillMin;
                        linePoint2[0] = x;
                        linePoint2[1] = e1.getY() * phaseY;
                        trans.pointValuesToPixel(linePoint1);
                        trans.pointValuesToPixel(linePoint2);
                        drawLine(canvas, linePoint1[0], linePoint1[1], linePoint2[0], linePoint2[1], dataSet.getFillColor(), dataSet.getFillAlpha());
                    }
                }

                currentStartIndex = index;
            }
        }
    }

    /**
     * Generates a path that is used for filled drawing.
     *
     * @param dataSet    The dataset from which to read the entries.
     * @param startIndex The index from which to start reading the dataset
     * @param endIndex   The index from which to stop reading the dataset
     * @param outputPath The path object that will be assigned the chart data.
     * @return
     */
    private void generateFilledPath(
            final ILineDataSet dataSet,
            final int startIndex,
            final int endIndex,
            final Path outputPath
    ) {
        final float fillMin = dataSet.getFillFormatter().getFillLinePosition(dataSet, mChart);

        final float phaseY = mAnimator.getPhaseY();

        outputPath.reset();

        final Entry entry = dataSet.getEntryForIndex(startIndex);

        outputPath.moveTo(entry.getX(), fillMin);
        outputPath.lineTo(entry.getX(), entry.getY() * phaseY);

        // create a new path
        Entry currentEntry = null;
        for (int x = startIndex + 1; x <= endIndex; x++) {

            currentEntry = dataSet.getEntryForIndex(x);

            outputPath.lineTo(currentEntry.getX(), currentEntry.getY() * phaseY);
        }

        // close up
        if (currentEntry != null) {
            outputPath.lineTo(currentEntry.getX(), fillMin);
        }

        outputPath.close();
    }

    /**
     * Generates a path that is used for filled drawing.
     *
     * @param dataSet    The dataset from which to read the entries.
     * @param startIndex The index from which to start reading the dataset
     * @param endIndex   The index from which to stop reading the dataset
     * @param outputPath The path object that will be assigned the chart data.
     * @return
     */
    private void generateFilledForLimitsPath(
            final ILineDataSet dataSet,
            final int startIndex,
            final int endIndex,
            final Float topLimitLine,
            final Float bottomLimitLine,
            final Path outputPath
    ) {

        final float fillMin = dataSet.getFillFormatter().getFillLinePosition(dataSet, mChart);

        final float phaseY = mAnimator.getPhaseY();

        outputPath.reset();

        final float limitFillLine = topLimitLine >= fillMin ? Float.max(bottomLimitLine, fillMin) : topLimitLine;

        final Entry entry = dataSet.getEntryForIndex(startIndex);

        outputPath.moveTo(entry.getX(), limitFillLine);
        Float pointY = Float.min(Float.min(topLimitLine, entry.getY()), Float.max(bottomLimitLine, entry.getY()));
        outputPath.lineTo(entry.getX(), pointY * phaseY);

        // create a new path
        Entry currentEntry = null;
        for (int x = startIndex + 1; x <= endIndex; x++) {

            currentEntry = dataSet.getEntryForIndex(x);

            float y = currentEntry.getY();
            if (y > topLimitLine) {
                y = topLimitLine;
            } else if (y < bottomLimitLine) {
                y = bottomLimitLine;
            }

            outputPath.lineTo(currentEntry.getX(), y * phaseY);
        }

        // close up
        if (currentEntry != null) {
            outputPath.lineTo(currentEntry.getX(), limitFillLine);
        }

        outputPath.close();
    }

    private void generateTopLimitsPath(
            final ILineDataSet dataSet,
            final int startIndex,
            final int endIndex,
            final Float topLimitLine,
            final Path outputPath
    ) {
        final float fillMin = dataSet.getFillFormatter().getFillLinePosition(dataSet, mChart);

        final float phaseY = mAnimator.getPhaseY();

        outputPath.reset();

        final float limitFillLine = topLimitLine > fillMin ? topLimitLine : fillMin;

        final Entry entry = dataSet.getEntryForIndex(startIndex);

        outputPath.moveTo(entry.getX(), limitFillLine);
        float pointY = Float.max(topLimitLine, entry.getY());
        outputPath.lineTo(entry.getX(), pointY * phaseY);

        // create a new path
        Entry currentEntry = null;
        for (int x = startIndex + 1; x <= endIndex; x++) {

            currentEntry = dataSet.getEntryForIndex(x);

            float y = Float.max(topLimitLine, currentEntry.getY());

            outputPath.lineTo(currentEntry.getX(), y * phaseY);
        }

        // close up
        if (currentEntry != null) {
            outputPath.lineTo(currentEntry.getX(), limitFillLine);
        }

        outputPath.close();
    }

    private void generateBottomLimitsPath(
            final ILineDataSet dataSet,
            final int startIndex,
            final int endIndex,
            final Float bottomLimitLine,
            final Path outputPath
    ) {
        final float fillMin = dataSet.getFillFormatter().getFillLinePosition(dataSet, mChart);

        final float phaseY = mAnimator.getPhaseY();

        outputPath.reset();

        final float limitFillLine = bottomLimitLine > fillMin ? fillMin : bottomLimitLine;

        final Entry entry = dataSet.getEntryForIndex(startIndex);

        outputPath.moveTo(entry.getX(), limitFillLine);
        float pointY = Float.min(bottomLimitLine, entry.getY());
        outputPath.lineTo(entry.getX(), pointY * phaseY);

        // create a new path
        Entry currentEntry = null;
        for (int x = startIndex + 1; x <= endIndex; x++) {

            currentEntry = dataSet.getEntryForIndex(x);

            float y = Float.min(bottomLimitLine, currentEntry.getY());

            outputPath.lineTo(currentEntry.getX(), y * phaseY);
        }

        // close up
        if (currentEntry != null) {
            outputPath.lineTo(currentEntry.getX(), limitFillLine);
        }

        outputPath.close();
    }

    @Override
    public void drawValues(Canvas c) {

        if (isDrawingValuesAllowed(mChart)) {

            List<ILineDataSet> dataSets = mChart.getLineData().getDataSets();

            for (int i = 0; i < dataSets.size(); i++) {

                ILineDataSet dataSet = dataSets.get(i);

                if (!shouldDrawValues(dataSet) || dataSet.getEntryCount() < 1)
                    continue;

                // apply the text-styling defined by the DataSet
                applyValueTextStyle(dataSet);

                Transformer trans = mChart.getTransformer(dataSet.getAxisDependency());

                // make sure the values do not interfear with the circles
                int valOffset = (int) (dataSet.getCircleRadius() * 1.75f);

                if (!dataSet.isDrawCirclesEnabled())
                    valOffset = valOffset / 2;

                mXBounds.set(mChart, dataSet);

                float[] positions = trans.generateTransformedValuesLine(dataSet, mAnimator.getPhaseX(), mAnimator
                        .getPhaseY(), mXBounds.min, mXBounds.max);

                MPPointF iconsOffset = MPPointF.getInstance(dataSet.getIconsOffset());
                iconsOffset.x = Utils.convertDpToPixel(iconsOffset.x);
                iconsOffset.y = Utils.convertDpToPixel(iconsOffset.y);

                for (int j = 0; j < positions.length; j += 2) {

                    float x = positions[j];
                    float y = positions[j + 1];

                    if (!mViewPortHandler.isInBoundsRight(x))
                        break;

                    if (!mViewPortHandler.isInBoundsLeft(x) || !mViewPortHandler.isInBoundsY(y))
                        continue;

                    Entry entry = dataSet.getEntryForIndex(j / 2 + mXBounds.min);

                    if (dataSet.isDrawValuesEnabled()) {
                        drawValue(c, dataSet.getValueFormatter(), entry.getY(), entry, i, x,
                                y - valOffset, dataSet.getValueTextColor(j / 2));
                    }

                    if (entry.getIcon() != null && dataSet.isDrawIconsEnabled()) {

                        Drawable icon = entry.getIcon();

                        Utils.drawImage(
                                c,
                                icon,
                                (int)(x + iconsOffset.x),
                                (int)(y + iconsOffset.y),
                                icon.getIntrinsicWidth(),
                                icon.getIntrinsicHeight());
                    }
                }

                MPPointF.recycleInstance(iconsOffset);
            }
        }
    }

    @Override
    public void drawExtras(Canvas c) {
        drawCircles(c);
    }

    /**
     * cache for the circle bitmaps of all datasets
     */
    private HashMap<IDataSet, DataSetImageCache> mImageCaches = new HashMap<>();

    /**
     * buffer for drawing the circles
     */
    private float[] mCirclesBuffer = new float[2];

    protected void drawCircles(Canvas c) {
        YAxis axisLeft = mChart.getAxis(YAxis.AxisDependency.LEFT);
        List<LimitLine> limitLines = axisLeft.getLimitLines();
        Float topLimitLine = null;
        Float bottomLimitLine = null;
        Integer limitColor = null;
        for (LimitLine line : limitLines) {
            limitColor = line.getLineColor();
            float limit = line.getLimit();
            if (topLimitLine == null || limit > topLimitLine) {
                topLimitLine = limit;
            }
            if (bottomLimitLine == null || limit < bottomLimitLine) {
                bottomLimitLine = limit;
            }
        }

        mRenderPaint.setStyle(Paint.Style.FILL);

        float phaseY = mAnimator.getPhaseY();

        mCirclesBuffer[0] = 0;
        mCirclesBuffer[1] = 0;

        List<ILineDataSet> dataSets = mChart.getLineData().getDataSets();

        for (int i = 0; i < dataSets.size(); i++) {

            ILineDataSet dataSet = dataSets.get(i);

            if (!dataSet.isVisible() || !dataSet.isDrawCirclesEnabled() ||
                    dataSet.getEntryCount() == 0)
                continue;

            mCirclePaintInner.setColor(dataSet.getCircleHoleColor());

            Transformer trans = mChart.getTransformer(dataSet.getAxisDependency());

            mXBounds.set(mChart, dataSet);

            float circleRadius = dataSet.getCircleRadius();

            int boundsRangeCount = mXBounds.range + mXBounds.min;

            for (int j = mXBounds.min; j <= boundsRangeCount; j++) {

                Entry e = dataSet.getEntryForIndex(j);

                if (e == null) break;

                mCirclesBuffer[0] = e.getX();
                mCirclesBuffer[1] = e.getY() * phaseY;

                trans.pointValuesToPixel(mCirclesBuffer);

                if (!mViewPortHandler.isInBoundsRight(mCirclesBuffer[0]))
                    break;

                if (!mViewPortHandler.isInBoundsLeft(mCirclesBuffer[0]) ||
                        !mViewPortHandler.isInBoundsY(mCirclesBuffer[1]))
                    continue;

                if (topLimitLine != null && (e.getY() >= topLimitLine || e.getY() <= bottomLimitLine)) {
                    mRenderPaint.setColor(limitColor);

                } else {
                    mRenderPaint.setColor(dataSet.getCircleColor(i));
                }
                c.drawCircle(mCirclesBuffer[0], mCirclesBuffer[1], circleRadius, mRenderPaint);
            }
        }
    }

    protected void drawLine(Canvas c, float startX, float startY, float stopX, float stopY, int fillColor, int fillAlpha) {

        int color = (fillAlpha << 24) | (fillColor & 0xffffff);
        // save
        Paint.Style previous = mRenderPaint.getStyle();
        int previousColor = mRenderPaint.getColor();

        // set
        mRenderPaint.setStyle(Paint.Style.FILL);
        mRenderPaint.setColor(color);

        c.drawLine(startX, startY, stopX, stopY, mRenderPaint);
        // restore
        mRenderPaint.setColor(previousColor);
        mRenderPaint.setStyle(previous);

    }

    @Override
    public void drawHighlighted(Canvas c, Highlight[] indices) {

        LineData lineData = mChart.getLineData();

        for (Highlight high : indices) {

            ILineDataSet set = lineData.getDataSetByIndex(high.getDataSetIndex());

            if (set == null || !set.isHighlightEnabled())
                continue;

            Entry e = set.getEntryForXValue(high.getX(), high.getY());

            if (!isInBoundsX(e, set))
                continue;

            MPPointD pix = mChart.getTransformer(set.getAxisDependency()).getPixelForValues(e.getX(), e.getY() * mAnimator
                    .getPhaseY());

            high.setDraw((float) pix.x, (float) pix.y);

            // draw the lines
            drawHighlightLines(c, (float) pix.x, (float) pix.y, set);
        }
    }

    /**
     * Sets the Bitmap.Config to be used by this renderer.
     * Default: Bitmap.Config.ARGB_8888
     * Use Bitmap.Config.ARGB_4444 to consume less memory.
     *
     * @param config
     */
    public void setBitmapConfig(Bitmap.Config config) {
        mBitmapConfig = config;
        releaseBitmap();
    }

    /**
     * Returns the Bitmap.Config that is used by this renderer.
     *
     * @return
     */
    public Bitmap.Config getBitmapConfig() {
        return mBitmapConfig;
    }

    /**
     * Releases the drawing bitmap. This should be called when {@link LineChart#onDetachedFromWindow()}.
     */
    public void releaseBitmap() {
        if (mBitmapCanvas != null) {
            mBitmapCanvas.setBitmap(null);
            mBitmapCanvas = null;
        }
        if (mDrawBitmap != null) {
            Bitmap drawBitmap = mDrawBitmap.get();
            if (drawBitmap != null) {
                drawBitmap.recycle();
            }
            mDrawBitmap.clear();
            mDrawBitmap = null;
        }
    }

    private class DataSetImageCache {

        private Path mCirclePathBuffer = new Path();

        private Bitmap[] circleBitmaps;

        /**
         * Sets up the cache, returns true if a change of cache was required.
         *
         * @param set
         * @return
         */
        protected boolean init(ILineDataSet set) {

            int size = set.getCircleColorCount();
            boolean changeRequired = false;

            if (circleBitmaps == null) {
                circleBitmaps = new Bitmap[size];
                changeRequired = true;
            } else if (circleBitmaps.length != size) {
                circleBitmaps = new Bitmap[size];
                changeRequired = true;
            }

            return changeRequired;
        }

        /**
         * Fills the cache with bitmaps for the given dataset.
         *
         * @param set
         * @param drawCircleHole
         * @param drawTransparentCircleHole
         */
        protected void fill(ILineDataSet set, boolean drawCircleHole, boolean drawTransparentCircleHole) {

            int colorCount = set.getCircleColorCount();
            float circleRadius = set.getCircleRadius();
            float circleHoleRadius = set.getCircleHoleRadius();

            for (int i = 0; i < colorCount; i++) {

                Bitmap.Config conf = Bitmap.Config.ARGB_4444;
                Bitmap circleBitmap = Bitmap.createBitmap((int) (circleRadius * 2.1), (int) (circleRadius * 2.1), conf);

                Canvas canvas = new Canvas(circleBitmap);
                circleBitmaps[i] = circleBitmap;
                mRenderPaint.setColor(set.getCircleColor(i));

                if (drawTransparentCircleHole) {
                    // Begin path for circle with hole
                    mCirclePathBuffer.reset();

                    mCirclePathBuffer.addCircle(
                            circleRadius,
                            circleRadius,
                            circleRadius,
                            Path.Direction.CW);

                    // Cut hole in path
                    mCirclePathBuffer.addCircle(
                            circleRadius,
                            circleRadius,
                            circleHoleRadius,
                            Path.Direction.CCW);

                    // Fill in-between
                    canvas.drawPath(mCirclePathBuffer, mRenderPaint);
                } else {

                    canvas.drawCircle(
                            circleRadius,
                            circleRadius,
                            circleRadius,
                            mRenderPaint);

                    if (drawCircleHole) {
                        canvas.drawCircle(
                                circleRadius,
                                circleRadius,
                                circleHoleRadius,
                                mCirclePaintInner);
                    }
                }
            }
        }

        /**
         * Returns the cached Bitmap at the given index.
         *
         * @param index
         * @return
         */
        protected Bitmap getBitmap(int index) {
            return circleBitmaps[index % circleBitmaps.length];
        }
    }
}
