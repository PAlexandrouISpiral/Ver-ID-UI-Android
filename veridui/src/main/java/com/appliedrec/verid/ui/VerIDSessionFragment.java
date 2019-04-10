package com.appliedrec.verid.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.YuvImage;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.media.ExifInterface;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.appliedrec.verid.core.Bearing;
import com.appliedrec.verid.core.EulerAngle;
import com.appliedrec.verid.core.FaceDetectionResult;
import com.appliedrec.verid.core.SessionResult;
import com.appliedrec.verid.core.SessionSettings;
import com.appliedrec.verid.core.Size;
import com.appliedrec.verid.core.VerIDImage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class VerIDSessionFragment extends Fragment implements IVerIDSessionFragment, ICameraPreviewView.CameraPreviewViewListener, Camera.PreviewCallback {

    static class CameraParams {
        Camera camera;
        int cameraOrientation;
        Camera.Size previewSize;
        int previewFormat;
        int facing;
        int deviceOrientation;
        int exifOrientation;
    }

    private ArrayList<ICameraPreviewView> cameraSurfaceViews = new ArrayList<>();
    private TransformableRelativeLayout cameraOverlaysView;

    public TransformableRelativeLayout getViewOverlays() {
        return viewOverlays;
    }

    private TransformableRelativeLayout viewOverlays;
    private DetectedFaceView detectedFaceView;
    private ThreadPoolExecutor previewProcessingExecutor;
    private VerIDSessionFragmentDelegate delegate;
    private VerIDImage currentImage;
    private ArrayList<CameraParams> cameras = new ArrayList<>();

    protected TextView instructionTextView;
    protected View instructionView;

    private static final int IMAGE_FORMAT_CERIDIAN_NV12 = 0x103;
    private int highlightedTextColour = 0xFFFFFFFF;
    private int neutralTextColour = 0xFF000000;
    private int highlightedColour = 0xFF36AF00;
    private int neutralColour = 0xFFFFFFFF;
    private int backgroundColour = 0x80000000;

    //region Fragment lifecycle

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        TransformableRelativeLayout view = new TransformableRelativeLayout(container.getContext());
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        view.setLayoutParams(layoutParams);
        view.setBackgroundResource(android.R.color.black);

        int cameraCount = Camera.getNumberOfCameras();
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        for (int i=0; i<cameraCount; i++) {
            Camera.getCameraInfo(i, cameraInfo);
            ICameraPreviewView cameraSurfaceView = createCameraView(cameraInfo.facing);
            cameraSurfaceView.setId(i+2);
            cameraSurfaceView.setListener(this);
            layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
            cameraSurfaceView.setLayoutParams(layoutParams);
            view.addView((View)cameraSurfaceView);
            cameraSurfaceViews.add(cameraSurfaceView);
        }

        ICameraPreviewView cameraSurfaceView = cameraSurfaceViews.get(0);
        cameraOverlaysView = new TransformableRelativeLayout(getActivity());
        layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        layoutParams.addRule(RelativeLayout.ALIGN_LEFT, cameraSurfaceView.getId());
        layoutParams.addRule(RelativeLayout.ALIGN_TOP, cameraSurfaceView.getId());
        layoutParams.addRule(RelativeLayout.ALIGN_RIGHT, cameraSurfaceView.getId());
        layoutParams.addRule(RelativeLayout.ALIGN_BOTTOM, cameraSurfaceView.getId());
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
        cameraOverlaysView.setLayoutParams(layoutParams);
        view.addView(cameraOverlaysView);

        viewOverlays = new TransformableRelativeLayout(getActivity());
        layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        viewOverlays.setLayoutParams(layoutParams);
        view.addView(viewOverlays);

        detectedFaceView = (DetectedFaceView) inflater.inflate(R.layout.detected_face_view, null, false);
        viewOverlays.addTransformableView(detectedFaceView);
        inflater.inflate(R.layout.verid_authentication_fragment, viewOverlays, true);
        instructionView = viewOverlays.findViewById(R.id.instruction);
        instructionView.setVisibility(View.GONE);
        instructionTextView = viewOverlays.findViewById(R.id.instruction_textview);
        instructionTextView.setText(R.string.preparing_face_detection);
        setTextViewColour(neutralColour, neutralTextColour);
        return view;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof VerIDSessionFragmentDelegate) {
            delegate = (VerIDSessionFragmentDelegate)context;
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        delegate = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() != null && ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        releaseCamera();
    }

    //endregion

    //region Camera preview listener

    @Override
    public void onCameraPreviewStarted(Camera camera) {

    }

    //endregion

    protected final void runOnUIThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    //region Camera

    protected ICameraPreviewView createCameraView(int facing) {
        if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return new CameraTextureView(getActivity(), null);
        } else {
            return new CameraSurfaceView(getActivity(), null);
        }
    }

    protected void setupCameras() {
        runOnUIThread(new Runnable() {
            @Override
            public void run() {
                Activity activity = getActivity();
                if (activity == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) || activity.isFinishing() || cameras.isEmpty()) {
                    return;
                }
                final Point displaySize = new Point(getView().getWidth(), getView().getHeight());
                Display display = ((WindowManager) activity.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
                int rotation = display.getRotation();
                int rotationDegrees = 0;
                switch (rotation) {
                    case Surface.ROTATION_0:
                        rotationDegrees = 0;
                        break;
                    case Surface.ROTATION_90:
                        rotationDegrees = 90;
                        break;
                    case Surface.ROTATION_180:
                        rotationDegrees = 180;
                        break;
                    case Surface.ROTATION_270:
                        rotationDegrees = 270;
                        break;
                }

                // From Android sample code
                int orientation = (rotationDegrees + 45) / 90 * 90;
                int i = 0;
                for (CameraParams cameraParams : cameras) {
                    final Camera.Parameters params = cameraParams.camera.getParameters();
                    int cameraRotation;
                    int orientationDegrees;
                    if (getDelegate() != null && getDelegate().getSessionSettings().getFacingOfCameraLens() == SessionSettings.LensFacing.BACK) {
                        cameraParams.deviceOrientation = (cameraParams.cameraOrientation - rotationDegrees + 360) % 360;
                        orientationDegrees = (cameraParams.cameraOrientation - rotationDegrees + 360) % 360;
                        cameraRotation = (cameraParams.cameraOrientation + orientation) % 360;
                    } else {
                        cameraParams.deviceOrientation = (cameraParams.cameraOrientation + rotationDegrees) % 360;
                        cameraParams.deviceOrientation = (360 - cameraParams.deviceOrientation) % 360;
                        orientationDegrees = (cameraParams.cameraOrientation + rotationDegrees) % 360;
                        cameraRotation = (cameraParams.cameraOrientation - orientation + 360) % 360;
                    }

                    switch (orientationDegrees) {
                        case 90:
                            cameraParams.exifOrientation = ExifInterface.ORIENTATION_ROTATE_90;
                            break;
                        case 180:
                            cameraParams.exifOrientation = ExifInterface.ORIENTATION_ROTATE_180;
                            break;
                        case 270:
                            cameraParams.exifOrientation = ExifInterface.ORIENTATION_ROTATE_270;
                            break;
                        default:
                            cameraParams.exifOrientation = ExifInterface.ORIENTATION_NORMAL;
                    }

                    final Point adjustedDisplaySize;
                    if (cameraRotation % 180 > 0) {
                        adjustedDisplaySize = new Point(displaySize.y, displaySize.x);
                    } else {
                        adjustedDisplaySize = new Point(displaySize);
                    }
                    float screenDensity = getResources().getDisplayMetrics().density;
                    adjustedDisplaySize.x /= screenDensity;
                    adjustedDisplaySize.y /= screenDensity;
                    cameraParams.previewSize = getOptimalCameraSizeForDimensions(params.getSupportedPreviewSizes(), adjustedDisplaySize.x, adjustedDisplaySize.y);
                    String previewFormats = params.get("preview-format-values");
                    if (previewFormats!=null && previewFormats.contains("fslNV21isNV12")) {
                        cameraParams.previewFormat = IMAGE_FORMAT_CERIDIAN_NV12;
                    } else {
                        cameraParams.previewFormat = params.getPreviewFormat();
                    }

                    final Camera.Size scaledSize;
                    if (cameraParams.deviceOrientation % 180 > 0) {
                        scaledSize = cameraParams.camera.new Size(cameraParams.previewSize.height, cameraParams.previewSize.width);
                    } else {
                        scaledSize = cameraParams.camera.new Size(cameraParams.previewSize.width, cameraParams.previewSize.height);
                    }
                    List<String> supportedFocusModes = params.getSupportedFocusModes();
                    if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                    } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                    params.setPreviewSize(cameraParams.previewSize.width, cameraParams.previewSize.height);
                    // Some camera drivers write the rotation to exif but some apply it to the raw jpeg data.
                    // If the camera rotation is set to 0 then we can use the exif orientation to right the image reliably.
                    params.setRotation(0);
                    updateCameraParams(params);
                    Log.d("CameraFocus", "setFocusMode "+params.getFocusMode());
                    cameraParams.camera.setParameters(params);
                    cameraParams.camera.setDisplayOrientation(cameraParams.deviceOrientation);

                    setPreviewCallbackWithBuffer(cameraParams);

                    float scale;
                    if ((float)displaySize.x / (float)displaySize.y > (float)scaledSize.width / (float)scaledSize.height) {
                        scale = (float)displaySize.x / (float)scaledSize.width;
                    } else {
                        scale = (float)displaySize.y / (float)scaledSize.height;
                    }
                    scaledSize.width *= scale;
                    scaledSize.height *= scale;
                    cameraSurfaceViews.get(i).setCamera(cameraParams.camera);
                    cameraSurfaceViews.get(i++).setFixedSize(scaledSize.width, scaledSize.height);
                }
            }
        });
    }

    private Camera.Size getOptimalCameraSizeForDimensions(List<Camera.Size> supportedSizes, final int desiredWidth, final int desiredHeight) {

        final int desiredSizeArea = desiredWidth * desiredHeight;
        final float desiredSizeAspectRatio = (float)desiredWidth/(float)desiredHeight;
        final boolean desiredSizeIsLandscape = desiredSizeAspectRatio > 1;

        Comparator<Camera.Size> comparator = new Comparator<Camera.Size>() {
            @Override
            public int compare(Camera.Size size1, Camera.Size size2) {
                float size1AspectRatio = (float)size1.width/(float)size1.height;
                float size2AspectRatio = (float)size2.width/(float)size2.height;
                boolean size1IsLandscape = size1AspectRatio > 1;
                boolean size2IsLandscape = size2AspectRatio > 1;
                if (size1IsLandscape == size2IsLandscape) {
                    int size1Area = size1.width * size1.height;
                    int size2Area = size2.width * size2.height;
                    float size1AreaDiff = Math.abs(size1Area - desiredSizeArea);
                    float size2AreaDiff = Math.abs(size2Area - desiredSizeArea);
                    if (size1AreaDiff == size2AreaDiff) {
                        float size1AspectRatioDiff = Math.abs(size1AspectRatio - desiredSizeAspectRatio);
                        float size2AspectRatioDiff = Math.abs(size2AspectRatio - desiredSizeAspectRatio);
                        if (size2AspectRatioDiff == size2AspectRatioDiff) {
                            return 0;
                        }
                        return size1AspectRatioDiff < size2AspectRatioDiff ? -1 : 1;
                    }
                    return size1AreaDiff < size2AreaDiff ? -1 : 1;
                } else {
                    return size1IsLandscape == desiredSizeIsLandscape ? -1 : 1;
                }
            }
        };

        Collections.sort(supportedSizes, comparator);

        return supportedSizes.get(0);
    }

    // Override if you need to update camera parameters while the camera is being initialized
    protected void updateCameraParams(Camera.Parameters params) {
    }


    protected void setPreviewCallbackWithBuffer(CameraParams camera) {
        final int bufferLength;
        if (camera.previewFormat == IMAGE_FORMAT_CERIDIAN_NV12) {
            bufferLength = (camera.previewSize.width * camera.previewSize.height * 12 + 7) / 8;
        } else {
            bufferLength = camera.previewSize.width * camera.previewSize.height * ImageFormat.getBitsPerPixel(camera.previewFormat) / 8;
        }
        camera.camera.addCallbackBuffer(new byte[bufferLength]);
        camera.camera.setPreviewCallbackWithBuffer(VerIDSessionFragment.this);
    }

    protected final void releaseCamera() {
        Iterator<CameraParams> iterator = cameras.iterator();
        while (iterator.hasNext()) {
            CameraParams params = iterator.next();
            params.camera.stopPreview();
            params.camera.release();
            iterator.remove();
        }
        runOnUIThread(new Runnable() {
            @Override
            public void run() {
                Iterator<ICameraPreviewView> iterator = cameraSurfaceViews.iterator();
                while (iterator.hasNext()) {
                    iterator.next().setCamera(null);
                    iterator.remove();
                }
            }
        });
    }

    //endregion

    /**
     * Indicates how to transform an image of the given size to fit to the fragment view.
     * @param size Image size
     * @return Transformation matrix
     * @since 1.0.0
     */
    public Matrix imageScaleTransformAtImageSize(Size size) {
        float width = (float)viewOverlays.getWidth();
        float height = (float)viewOverlays.getHeight();
        float viewAspectRatio = width / height;
        float imageAspectRatio = (float)size.width / (float)size.height;
        RectF rect = new RectF();
        if (imageAspectRatio > viewAspectRatio) {
            rect.bottom = size.height;
            float w = size.height * viewAspectRatio;
            rect.left = size.width / 2 - w / 2;
            rect.right = size.width / 2 + w / 2;
        } else {
            rect.right = size.width;
            float h = size.width / viewAspectRatio;
            rect.top = size.height / 2 - h / 2;
            rect.bottom = size.height / 2 + h / 2;
        }
        float scale = width / rect.width();
        Matrix matrix = new Matrix();
        matrix.setTranslate(0-rect.left, 0-rect.top);
        matrix.postScale(scale, scale);
        return matrix;
    }

    protected VerIDSessionFragmentDelegate getDelegate() {
        return delegate;
    }

    //region Ver-ID session fragment interface

    public void startCamera() {
        if (previewProcessingExecutor == null || previewProcessingExecutor.isShutdown()) {
            previewProcessingExecutor = new ThreadPoolExecutor(0, 1, Long.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
        }
        previewProcessingExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    int numberOfCameras = Camera.getNumberOfCameras();
                    cameras.clear();
                    for (int i=0; i<numberOfCameras; i++) {
                        CameraParams params = new CameraParams();
                        Camera.CameraInfo info = new Camera.CameraInfo();
                        Camera.getCameraInfo(i, info);
                        params.camera = Camera.open(i);
                        params.cameraOrientation = info.orientation;
                        params.facing = info.facing;
                        cameras.add(params);
                    }
                    if (cameras.isEmpty()) {
                        throw new Exception("Unable to open a camera");
                    }
                    setupCameras();
                } catch (final Exception e) {
                    runOnUIThread(new Runnable() {
                        @Override
                        public void run() {
                            if (delegate != null) {
                                delegate.veridSessionFragmentDidFailWithError(VerIDSessionFragment.this, e);
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void drawFaceFromResult(FaceDetectionResult faceDetectionResult, SessionResult sessionResult, RectF defaultFaceBounds, EulerAngle offsetAngleFromBearing) {
        @Nullable String labelText;
        boolean isHighlighted;
        RectF ovalBounds;
        @Nullable RectF cutoutBounds;
        @Nullable EulerAngle faceAngle;
        boolean showArrow;
        if (getDelegate() == null || getDelegate().getSessionSettings() == null) {
            return;
        }
        SessionSettings sessionSettings = getDelegate().getSessionSettings();
        if (sessionSettings != null && sessionResult.getAttachments().length >= sessionSettings.getNumberOfResultsToCollect()) {
            labelText = getString(R.string.please_wait);
            isHighlighted = true;
            ovalBounds = faceDetectionResult.getFaceBounds() != null ? faceDetectionResult.getFaceBounds() : defaultFaceBounds;
            cutoutBounds = null;
            faceAngle = null;
            showArrow = false;
        } else {
            switch (faceDetectionResult.getStatus()) {
                case FACE_FIXED:
                case FACE_ALIGNED:
                    labelText = getString(R.string.great_hold_it);
                    isHighlighted = true;
                    ovalBounds = faceDetectionResult.getFaceBounds() != null ? faceDetectionResult.getFaceBounds() : defaultFaceBounds;
                    cutoutBounds = null;
                    faceAngle = null;
                    showArrow = false;
                    break;
                case FACE_MISALIGNED:
                    labelText = getString(R.string.slowly_turn_to_follow_arror);
                    isHighlighted = false;
                    ovalBounds = faceDetectionResult.getFaceBounds() != null ? faceDetectionResult.getFaceBounds() : defaultFaceBounds;
                    cutoutBounds = null;
                    faceAngle = faceDetectionResult.getFaceAngle();
                    showArrow = true;
                    break;
                case FACE_TURNED_TOO_FAR:
                    labelText = null;
                    isHighlighted = false;
                    ovalBounds = faceDetectionResult.getFaceBounds() != null ? faceDetectionResult.getFaceBounds() : defaultFaceBounds;
                    cutoutBounds = null;
                    faceAngle = null;
                    showArrow = false;
                    break;
                default:
                    labelText = getString(R.string.move_face_into_oval);
                    isHighlighted = false;
                    ovalBounds = defaultFaceBounds;
                    cutoutBounds = faceDetectionResult.getFaceBounds();
                    faceAngle = null;
                    showArrow = false;
            }
        }
        Matrix matrix = imageScaleTransformAtImageSize(faceDetectionResult.getImageSize());
        matrix.mapRect(ovalBounds);
        if (cutoutBounds != null) {
            matrix.mapRect(cutoutBounds);
        }
        drawCameraOverlay(faceDetectionResult.getRequestedBearing(), labelText, isHighlighted, ovalBounds, cutoutBounds, faceAngle, showArrow, offsetAngleFromBearing);
    }

    @Override
    public void clearCameraOverlay() {
        instructionView.setVisibility(View.GONE);
        detectedFaceView.setFaceRect(null, null, neutralColour, backgroundColour, null, null);
    }

    private void drawCameraOverlay(Bearing bearing, @Nullable String text, boolean isHighlighted, RectF ovalBounds, @Nullable RectF cutoutBounds, @Nullable EulerAngle faceAngle, boolean showArrow, @Nullable EulerAngle offsetAngleFromBearing) {
        if (getActivity() == null) {
            return;
        }
        instructionTextView.setText(text);
        instructionTextView.setTextColor(isHighlighted ? highlightedTextColour : neutralTextColour);
        instructionTextView.setBackgroundColor(isHighlighted ? highlightedColour : neutralColour);
        instructionView.setVisibility(text != null ? View.VISIBLE : View.GONE);

        int colour = isHighlighted ? highlightedColour : neutralColour;
        int textColour = isHighlighted ? highlightedTextColour : neutralTextColour;
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(instructionView.getLayoutParams());
        params.topMargin = (int)(ovalBounds.top - instructionView.getHeight() - getResources().getDisplayMetrics().density * 16f);
        instructionView.setLayoutParams(params);
        setTextViewColour(colour, textColour);
        Double angle = null;
        Double distance = null;
        if (faceAngle != null && offsetAngleFromBearing != null && showArrow) {
            angle = Math.atan2(offsetAngleFromBearing.getPitch(), offsetAngleFromBearing.getYaw());
            distance = Math.hypot(offsetAngleFromBearing.getYaw(), 0-offsetAngleFromBearing.getPitch()) * 2;
        }
        detectedFaceView.setFaceRect(ovalBounds, cutoutBounds, colour, backgroundColour, angle, distance);
    }

    private void setTextViewColour(int background, int text) {
        float density = getResources().getDisplayMetrics().density;
        float[] corners = new float[8];
        Arrays.fill(corners, 10 * density);
        ShapeDrawable shapeDrawable = new ShapeDrawable(new RoundRectShape(corners, null, null));
        shapeDrawable.setPadding((int)(8f * density), (int)(4f * density), (int)(8f * density), (int)(4f * density));
        shapeDrawable.getPaint().setColor(background);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            instructionTextView.setBackgroundDrawable(shapeDrawable);
        } else {
            instructionTextView.setBackground(shapeDrawable);
        }
        instructionTextView.setTextColor(text);
    }

    @Override
    public VerIDImage dequeueImage() throws Exception {
        synchronized (this) {
            while (currentImage == null) {
                this.wait();
            }
            VerIDImage image = currentImage;
            currentImage = null;
            return image;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        CameraParams cameraParams = null;
        for (CameraParams params : cameras) {
            if (camera == params.camera) {
                cameraParams = params;
                break;
            }
        }
        if (cameraParams == null) {
            return;
        }
        Log.d("Ver-ID", "Received a frame from camera facing "+(cameraParams.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "front": "back"));
        synchronized (this) {
            if (currentImage == null) {
                final byte[] dataCopy = Arrays.copyOf(data, data.length);
                camera.addCallbackBuffer(data);
                YuvImage image = new YuvImage(dataCopy, cameraParams.previewFormat, cameraParams.previewSize.width, cameraParams.previewSize.height, null);
                currentImage = new VerIDImage(image, cameraParams.exifOrientation);
                notify();
            } else {
                camera.addCallbackBuffer(data);
            }
        }
    }
}
