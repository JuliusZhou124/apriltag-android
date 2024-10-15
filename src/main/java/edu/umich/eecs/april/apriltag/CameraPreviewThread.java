package edu.umich.eecs.april.apriltag;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.camera2.internal.CameraDeviceStateCallbacks;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraX;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.CameraCaptureCallback;
import androidx.camera.lifecycle.ProcessCameraProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**

 This class is responsible for managing the camera and live preview. It also enqueues image previews
 in the DetectionThread for asynchronous Apriltag detection on a separate view.
 <p>
 This class also displays a text view with the current frames per second (FPS) of the camera thread.
 </p>
 */
// Through capture requests we can forward an image to be processed. Instantiated and run from the ApriltagDetectorActivity Class.
// Looks like I may need to figure out how to get a camera working in the first place as a Main activity ...
public class CameraPreviewThread extends Thread {
    private static final String TAG = "CameraPreviewThread";

    private String mCameraId;
    private CameraManager mCameraManager;
    private final SurfaceHolder mSurfaceHolder;
    private final DetectionThread mDetectionThread;
    private Camera mCamera;
    private final TextView mFpsTextView;

    private long mLastRender = System.currentTimeMillis();
    private int mFrameCount = 0;
    private final SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera.setPreviewDisplay(holder);

                // Set the preview callback to receive camera frames asynchronously
                mCamera.setPreviewCallback((data, camera) -> {
                    try {
                        mDetectionThread.enqueueCameraFrame(data, camera.getParameters().getPreviewSize());
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Interrupted while enqueuing camera frame: " + e.getMessage());
                    }

                    previewFpsCallback();
                });

                mCamera.startPreview();
            } catch (IOException e) {
                Log.e(TAG, "Error setting camera preview: " + e.getMessage());
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // Do nothing
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Do nothing
        }
    };

    private final CameraDevice.StateCallback mCameraCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {

        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {

        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i){
            // How does android do this again?
            String errorMsg;
            switch (i){
                case ERROR_CAMERA_DEVICE:
                    errorMsg = "Fatal (device)";
                    break;
                case ERROR_CAMERA_DISABLED:
                    errorMsg = "Device policy";
                    break;
                case ERROR_CAMERA_IN_USE:
                    errorMsg = "Camera in use";
                    break;
                case ERROR_CAMERA_SERVICE:
                    errorMsg = "Fatal (service)";
                    break;
                case ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = "Maximum cameras in use";
                    break;
            }
        }
    };

    public CameraPreviewThread(SurfaceHolder surfaceHolder, DetectionThread detectionThread, TextView fpsTextView) {
        mSurfaceHolder = surfaceHolder;
        mFpsTextView = fpsTextView;
        mDetectionThread = detectionThread;

        mSurfaceHolder.addCallback(mCallback);
    }


    private void setupCamera() {
        try {
            String[] cameraIds = mCameraManager.getCameraIdList();

            for (String id: cameraIds) {
                CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(id);

                //If we want to choose the rear facing camera instead of the front facing one
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }

                StreamConfigurationMap streamConfigurationMap = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                if (streamConfigurationMap != null) {
                    //previewSize = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.height * it.width };

                    Size previewSize = Collections.max(Arrays.asList(cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG)), (size1, size2) -> Integer.compare(size1.getHeight() * size1.getWidth(), size2.getHeight() * size2.getWidth()));
//                    Size previewSize = Arrays.stream(
//                                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG))
//                            .max((size1, size2) -> Integer.compare(size1.getHeight() * size1.getWidth(), size2.getHeight() * size2.getWidth()))
    //                            .orElse(null);
                    Size videoSize = Collections.max(Arrays.asList(cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(MediaRecorder.class)), (size1, size2) -> Integer.compare(size1.getHeight() * size1.getWidth(), size2.getHeight() * size2.getWidth()));

                   //         cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(MediaRecorder.class).maxByOrNull { it.height * it.width }!!;
                    ImageReader imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 1);
//                    imageReader.setOnImageAvailableListener(onImageAvailableListener, backgroundHandler);
                }
                mCameraId = id;
            }
        }
        catch (Exception e) {
            System.out.println(e.toString());
        }
    }

    public void destroy() {
        mSurfaceHolder.removeCallback(mCallback);
        mCamera.setPreviewCallback(null);
        mCamera.stopPreview();
        mCamera.release();
        mCamera = null;
    }

    private void previewFpsCallback() {
        long now = System.currentTimeMillis();
        long diff = now - mLastRender;
        mFrameCount++;
        if (diff >= 1000) {
            double fps = 1000.0 / diff * mFrameCount;
            mFpsTextView.setText(String.format("%.2f fps Camera", fps));
            mLastRender = now;
            mFrameCount = 0;
        }
    }

    @Override
    public void run() {
        // Stop the previous camera preview
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
                Log.i(TAG, "Camera stop");
            } catch (Exception e) {
                Log.e(TAG, "Unable to stop camera: " + e);
            }
        }

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
            mCamera.startPreview();
            Log.i(TAG, "Camera preview start");
        } catch (IOException e) {
            Log.e(TAG, "Error setting camera preview: " + e.getMessage());
        }
    }

    protected void initialize() {
        int camidx = 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i += 1) {
            Camera.getCameraInfo(i, info);
            int desiredFacing = Camera.CameraInfo.CAMERA_FACING_BACK;
            if (info.facing == desiredFacing) {
                camidx = i;
                break;
            }
        }

        try {
            mCamera = Camera.open(camidx);
            Log.i(TAG, "using camera " + camidx);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't open camera: " + e.getMessage());
            return;
        }

        Camera.getCameraInfo(camidx, info);
        setCameraParameters(mCamera, info);
    }

    private void setCameraParameters(Camera camera, Camera.CameraInfo info)
    {
        Camera.Parameters parameters = camera.getParameters();

        List<Camera.Size> sizeList = camera.getParameters().getSupportedPreviewSizes();
        Camera.Size bestSize = null;
        for (int i = 0; i < sizeList.size(); i++) {
            Camera.Size candidateSize = sizeList.get(i);
            Log.i(TAG, " " + candidateSize.width + "x" + candidateSize.height + " (" + candidateSize.width * candidateSize.height + " area)");
            if (bestSize == null || (candidateSize.width * candidateSize.height) > (bestSize.width * bestSize.height)) {
                if (candidateSize.width != candidateSize.height) {
                    bestSize = candidateSize;
                }
            }
        }
        parameters.setPreviewSize(bestSize.width, bestSize.height);
        Log.i(TAG, "Setting " + bestSize.width + " x " + bestSize.height);

        List<String> focusModes = parameters.getSupportedFocusModes();
        if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            Log.i(TAG, "Setting focus mode for continuous video");
        } else {
            Log.i(TAG, "Focus mode for continuous video not supported, skipping");
        }

        int[] desiredFpsRange = new int[] { 15000, 15000 };
        List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
        Log.i(TAG, "Supported FPS ranges:");
        for (int[] range : fpsRanges) {
            Log.i(TAG, "  [" + range[0] + ", " + range[1] + "]");
            if (range[0] == desiredFpsRange[0] && range[1] == desiredFpsRange[1]) {
                parameters.setPreviewFpsRange(range[0], range[1]);
                Log.i(TAG, "Setting FPS range [" + range[0] + ", " + range[1] + "]");
                break;
            }
        }

        camera.setDisplayOrientation(info.orientation % 360);

        camera.setParameters(parameters);
    }
}