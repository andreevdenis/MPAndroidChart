
package com.github.mikephil.charting.charts;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;
import com.github.mikephil.charting.renderer.LineChartRenderer;

/**
 * Chart that draws lines, surfaces, circles, ...
 *
 * @author Philipp Jahoda
 */
public class LineChart extends BarLineChartBase<LineData> implements LineDataProvider {

    public LineChart(Context context) {
        super(context);
    }

    public LineChart(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LineChart(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void init() {
        super.init();

        mRenderer = new LineChartRenderer(this, mAnimator, mViewPortHandler);
    }

    @Override
    public LineData getLineData() {
        return mData;
    }

    @Override
    protected void onDetachedFromWindow() {
        // releases the bitmap in the renderer to avoid oom error
        if (mRenderer != null && mRenderer instanceof LineChartRenderer) {
            ((LineChartRenderer) mRenderer).releaseBitmap();
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = true;
        Log.d(LineChart.class.getName(), "onTouchEvent x="+event.getX()+";y="+event.getY());
        // if there is no marker view or drawing marker is disabled
        if (isShowingMarker() && this.getMarker() instanceof MarkerView){
            MarkerView markerView = (MarkerView) this.getMarker();
            Rect rect = new Rect((int)markerView.drawingPosX,(int)markerView.drawingPosY,(int)markerView.drawingPosX + markerView.getWidth(), (int)markerView.drawingPosY + markerView.getHeight());
            Log.d(LineChart.class.getName(), "onTouchEvent marker rect="+rect);
            if (rect.contains((int) event.getX(),(int) event.getY())) {
                // touch on marker -> dispatch touch event in to marker
                markerView.dispatchTouchEvent(event);
            }else{
                handled = super.onTouchEvent(event);
            }
        }else{
            handled = super.onTouchEvent(event);
        }
        return handled;
    }

    private boolean isShowingMarker(){
        return mMarker != null && isDrawMarkersEnabled() && valuesToHighlight();
    }
}