package com.appliedrec.verid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

/**
 * Created by jakub on 16/02/2018.
 */

public class
DetectedFaceView extends View {

    Paint strokePaint;
    Paint faceTemplatePaint;
    RectF faceRect;
    RectF templateRect;
    RectF viewRect;
    Double angle;
    Double distance;
    private Path path = new Path();
    private Path arrowPath = new Path();
    private Path templatePath = new Path();

    public DetectedFaceView(Context context) {
        super(context);
        init(context);
    }

    public DetectedFaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        setWillNotDraw(false);
        float screenDensity = context.getResources().getDisplayMetrics().density;
        strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(screenDensity * 8f);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);

        faceTemplatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        faceTemplatePaint.setStyle(Paint.Style.FILL);
        faceTemplatePaint.setColor(Color.argb(128, 0, 0, 0));

        templatePath.setFillType(Path.FillType.WINDING);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        path.rewind();
        arrowPath.rewind();
        templatePath.rewind();
        if (templateRect != null) {
            viewRect = new RectF(0, 0, getWidth(), getHeight());
            templatePath.addRect(viewRect, Path.Direction.CCW);
            templatePath.addOval(templateRect, Path.Direction.CW);
            canvas.drawPath(templatePath, faceTemplatePaint);
        }
        if (faceRect != null) {
            strokePaint.setStrokeWidth(faceRect.width() * 0.038f);
            strokePaint.setShadowLayer(15, 0, 0, Color.argb(0x33, 0, 0, 0));
            path.addOval(faceRect, Path.Direction.CW);
            canvas.drawPath(path, strokePaint);
            if (angle != null && distance != null) {
                drawArrow(canvas, angle, distance);
            }
        }
    }

    public void setFaceRect(RectF faceRect, RectF templateRect, int faceRectColour, int faceBackgroundColour, Double angle, Double distance) {
        this.faceRect = faceRect;
        this.templateRect = templateRect;
        this.strokePaint.setColor(faceRectColour);
        this.faceTemplatePaint.setColor(faceBackgroundColour);
        this.angle = angle;
        this.distance = distance;
        postInvalidate();
    }

    private void drawArrow(Canvas canvas, double angle, double distance) {
        double arrowLength = faceRect.width() / 5;
        distance *= 1.7;
        double arrowStemLength = Math.min(Math.max(arrowLength * distance, arrowLength * 0.75), arrowLength * 1.7);
        double arrowAngle = Math.toRadians(40);
        float arrowTipX = (float)(faceRect.centerX() + Math.cos(angle) * arrowLength / 2);
        float arrowTipY = (float)(faceRect.centerY() + Math.sin(angle) * arrowLength / 2);
        float arrowPoint1X = (float)(arrowTipX + Math.cos(angle + Math.PI - arrowAngle) * arrowLength * 0.6);
        float arrowPoint1Y = (float)(arrowTipY + Math.sin(angle + Math.PI - arrowAngle) * arrowLength * 0.6);
        float arrowPoint2X = (float)(arrowTipX + Math.cos(angle + Math.PI + arrowAngle) * arrowLength * 0.6);
        float arrowPoint2Y = (float)(arrowTipY + Math.sin(angle + Math.PI + arrowAngle) * arrowLength * 0.6);
        float arrowStartX = (float)(arrowTipX + Math.cos(angle + Math.PI) * arrowStemLength);
        float arrowStartY = (float)(arrowTipY + Math.sin(angle + Math.PI) * arrowStemLength);


        arrowPath.moveTo(arrowPoint1X, arrowPoint1Y);
        arrowPath.lineTo(arrowTipX, arrowTipY);
        arrowPath.lineTo(arrowPoint2X, arrowPoint2Y);
        arrowPath.moveTo(arrowTipX, arrowTipY);
        arrowPath.lineTo(arrowStartX, arrowStartY);

        canvas.drawPath(arrowPath, strokePaint);
    }
}
