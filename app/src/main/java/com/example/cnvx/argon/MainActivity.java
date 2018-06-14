package com.example.cnvx.argon;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    // Maximum preview resolution supported by the camera2 API
    private static final int MAXIMUM_PREVIEW_WIDTH = 1920;
    private static final int MAXIMUM_PREVIEW_HEIGHT = 1080;

    private static final int CAMERA_REQUEST_CODE = 1;
    private boolean startClassifying;
    private FitTextureView cameraPreview;
    private TextView labelText;
    private RelativeLayout overlayContainer;
    private ImageView previewOverlay;
    private ViewTreeObserver observer;
    private ImageReader imageReader;
    private Size previewResolution;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;
    private CobaltClassifier classifier = new CobaltClassifier();
    private Activity activity = MainActivity.this;

    // Camera identifier
    private String camera;

    // Semaphore (https://en.wikipedia.org/wiki/Semaphore_(programming)) for the camera
    private Semaphore cameraLock = new Semaphore(1);

    // Thread and handler for running background tasks
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    // Used to make sure the threads stay synchronized
    private final Object lock = new Object();

    // Listener for the camera preview
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            initializeCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            setPreviewTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    // Called when the camera changes its state
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice currentCameraDevice) {
           cameraLock.release();
           cameraDevice = currentCameraDevice;
           createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice currentCameraDevice) {
            cameraLock.release();
            currentCameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice currentCameraDevice, int i) {
            cameraLock.release();
            currentCameraDevice.close();
            cameraDevice = null;

            if (activity != null) {
                activity.finish();
            }
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = cameraPreview.getSurfaceTexture();

            if (texture != null) {
                texture.setDefaultBufferSize(previewResolution.getWidth(), previewResolution.getHeight());
                Surface surface = new Surface(texture);
                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequestBuilder.addTarget(surface);

                // Create the CameraCaptureSession for the preview
                cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                        if (cameraDevice != null) {
                            captureSession = cameraCaptureSession;

                            try {
                                // Enable auto focus
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                                // Start displaying the camera preview
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(previewRequest,
                                        captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                        displayText("Failed to configure CameraCaptureSession");
                    }
                }, null);
            }
            else {
                throw new RuntimeException("Time out while waiting to lock camera opening");
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {}
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {}
    };

    private void displayText(final String text) {

        if (activity != null) {
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    labelText.setText(text);
                }
            });
        }
    }

    // Set up the camera and preview
    private void initializeCamera(int width, int height) {

        // Make sure the user allowed access to the camera
        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {

            // Request camera permission
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST_CODE);

        } else {
            CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);

            // Get a list of available cameras and pick one
            try {
                for (String id : manager.getCameraIdList()) {
                    CameraCharacteristics info = manager.getCameraCharacteristics(id);
                    Integer direction = info.get(CameraCharacteristics.LENS_FACING);

                    // Make sure the it's on the back of the phone
                    if (direction != null && direction != CameraCharacteristics.LENS_FACING_FRONT) {
                        StreamConfigurationMap stream = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        // Avoid NullPointerException
                        if (stream != null) {

                            Size[] resolutions = stream.getOutputSizes(ImageFormat.JPEG);
                            Size outputResolution = new Size(0, 0);

                            // Get the highest preview resolution outputted by the camera
                            for (int index = 0; resolutions != null && index < resolutions.length; index++) {
                                if (resolutions[index].getWidth() * resolutions[index].getHeight() >
                                        outputResolution.getWidth() * outputResolution.getHeight()) {
                                    outputResolution = resolutions[index];
                                }
                            }

                            // Create an ImageReader that can hold 2 images in memory
                            imageReader = ImageReader.newInstance(outputResolution.getWidth(),
                                    outputResolution.getHeight(), ImageFormat.JPEG, 2);

                            Point screenSize = new Point();
                            activity.getWindowManager().getDefaultDisplay().getSize(screenSize);

                            // Resolution of the TextureView used to render the preview
                            int previewWidth = width;
                            int previewHeight = height;

                            // Resolution of the screen
                            int maxPreviewWidth = screenSize.x;
                            int maxPreviewHeight = screenSize.y;

                            boolean rotate = false;
                            int cameraOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
                            int screenOrientation = activity.getWindowManager().getDefaultDisplay().getRotation();

                            // Match the camera orientation with the screen orientation
                            switch (screenOrientation) {
                                case Surface.ROTATION_0:
                                    rotate = false;
                                    break;
                                case Surface.ROTATION_180:
                                    if (cameraOrientation == 90 || cameraOrientation == 270) {
                                        rotate = true;
                                    }
                                    break;
                                case Surface.ROTATION_90:
                                    rotate = false;
                                    break;
                                case Surface.ROTATION_270:
                                    if (cameraOrientation == 0 || cameraOrientation == 180) {
                                        rotate = true;
                                    }
                                    break;
                            }

                            if (rotate) {
                                previewWidth = height;
                                previewHeight = width;
                                maxPreviewWidth = screenSize.y;
                                maxPreviewHeight = screenSize.x;
                            }

                            if (maxPreviewWidth > MAXIMUM_PREVIEW_WIDTH)
                                maxPreviewWidth = MAXIMUM_PREVIEW_WIDTH;

                            if (maxPreviewHeight > MAXIMUM_PREVIEW_HEIGHT)
                                maxPreviewHeight = MAXIMUM_PREVIEW_HEIGHT;

                            Size[] outputSizes = stream.getOutputSizes(SurfaceTexture.class);
                            List<Size> biggerThanPreview = new ArrayList<>();
                            List<Size> smallerThanPreview = new ArrayList<>();

                            // Get the highest preview resolution outputted by the camera
                            for (int index = 0; outputSizes != null && index < outputSizes.length; index++) {

                                // Make sure it doesn't exceed the resolution of the screen
                                if (outputSizes[index].getWidth() <= maxPreviewWidth &&
                                        outputSizes[index].getHeight() <= maxPreviewHeight) {

                                    // Make sure the aspect ratio is correct
                                    if (outputSizes[index].getWidth() == outputSizes[index].getHeight() *
                                            outputResolution.getWidth() / outputResolution.getHeight()) {

                                        if (outputSizes[index].getWidth() >= previewWidth &&
                                                outputSizes[index].getHeight() >= previewHeight) {
                                            biggerThanPreview.add(outputSizes[index]);
                                        } else {
                                            smallerThanPreview.add(outputSizes[index]);
                                        }
                                    }
                                }
                            }

                            // Convert to arrays
                            Size[] bigger = new Size[biggerThanPreview.size()];
                            Size[] smaller = new Size[smallerThanPreview.size()];
                            biggerThanPreview.toArray(bigger);
                            smallerThanPreview.toArray(smaller);

                            Size finalPreviewResolution;
                            if (bigger.length != 0) {
                                Size smallest = bigger[0];

                                for (int index = 0; index < bigger.length; index++) {
                                    if (bigger[index].getWidth() * bigger[index].getHeight() <
                                            smallest.getWidth() * smallest.getHeight())
                                        smallest = bigger[index];
                                }

                                finalPreviewResolution = smallest;
                            } else if (smaller.length != 0) {
                                Size biggest = smaller[0];

                                for (int index = 0; index < smaller.length; index++) {
                                    if (smaller[index].getWidth() * smaller[index].getHeight() >
                                            biggest.getWidth() * biggest.getHeight())
                                        biggest = smaller[index];
                                }

                                finalPreviewResolution = biggest;
                            } else {
                                // If all else fails use the first resolution
                                finalPreviewResolution = outputSizes[0];
                            }

                            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                cameraPreview.setAspectRatio(finalPreviewResolution.getWidth(),
                                        finalPreviewResolution.getHeight());
                            } else {
                                cameraPreview.setAspectRatio(finalPreviewResolution.getHeight(),
                                        finalPreviewResolution.getWidth());
                            }

                            previewResolution = finalPreviewResolution;
                            camera = id;
                            setPreviewTransform(previewWidth, previewHeight);

                            try {
                                if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                                    throw new RuntimeException("Time out while waiting to lock camera opening");
                                }

                                manager.openCamera(camera, stateCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            } catch (InterruptedException e) {
                                throw new RuntimeException("Interrupted while trying to lock camera opening");
                            }
                        }
                    }
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
                // NullPointerException thrown when android.hardware.camera2 is unsupported
                e.printStackTrace();
            }
        }
    }

    private void closeCamera() {
        try {
            cameraLock.acquire();

            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing");
        } finally {
            cameraLock.release();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Restart the activity
            Intent intent = getIntent();
            activity.finish();
            startActivity(intent);
        } else {
            activity.finish();
        }
    }

    // Set up the transform for the TextureView
    private void setPreviewTransform(int previewWidth, int previewHeight) {

        if (activity != null && previewResolution != null && cameraPreview != null ) {
            int orientation = activity.getWindowManager().getDefaultDisplay().getRotation();
            Matrix matrix = new Matrix();
            RectF viewRectangle = new RectF(0, 0, previewWidth, previewHeight);
            RectF bufferRectangle = new RectF(0, 0,
                    previewResolution.getHeight(), previewResolution.getWidth());

            // The center of the preview
            PointF center = new PointF(viewRectangle.centerX(), viewRectangle.centerY());

            if (Surface.ROTATION_90 == orientation || Surface.ROTATION_270 == orientation) {
                bufferRectangle.offset(center.x - bufferRectangle.centerX(), center.y - bufferRectangle.centerY());
                matrix.setRectToRect(viewRectangle, bufferRectangle, Matrix.ScaleToFit.FILL);

                float scale = Math.max((float) previewHeight / previewResolution.getHeight(),
                        (float) previewWidth / previewResolution.getWidth());

                matrix.postRotate(90 * (orientation - 2), center.x, center.y);
                matrix.postScale(scale, scale, center.x, center.y);
            } else if (Surface.ROTATION_180 == orientation) {
                matrix.postRotate(180, center.x, center.y);
            }

            cameraPreview.setTransform(matrix);
        }
    }

    // Make sure the orientation of the bitmap matches the camera preview
    private Bitmap rotateBitmap(Bitmap bitmap) {
        int orientation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();

        if (Surface.ROTATION_90 == orientation || Surface.ROTATION_270 == orientation) {
            matrix.postRotate(90 * (orientation - 2));
        } else if (Surface.ROTATION_180 == orientation) {
            matrix.postRotate(180);
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
    }

    // Try to classify a single image from the camera
    private void classifyImage() {
        if (activity != null && classifier != null && cameraDevice != null) {

            // Use the TextureView as input, it's lower resolution but much faster
            Bitmap image = rotateBitmap(cameraPreview.getBitmap());

            // Get the classification and show it
            displayText(classifier.classify(image));

            // Clear the memory for the next classification
            image.recycle();
        } else {
            displayText("Initializing");
        }
    }

    // Classify input from the camera asynchronously, to be run on the background thread
    private Runnable repeatedlyClassify = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (startClassifying)
                    classifyImage();
            }

            // Post the runnable through the handler
            backgroundHandler.post(repeatedlyClassify);
        }
    };

    // Start a background thread and create a handler for it
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("background_thread");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        synchronized (lock) {
            startClassifying = true;
        }

        backgroundHandler.post(repeatedlyClassify);
    }

    // Safely stop the thread and it's handler
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();

        try {
            synchronized (lock) {
                startClassifying = false;
            }

            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startClassifying = false;
        setContentView(R.layout.activity_main);

        // Get the views
        cameraPreview = (FitTextureView) findViewById(R.id.camera_preview);
        labelText = (TextView) findViewById(R.id.label_text);
        overlayContainer = (RelativeLayout) findViewById(R.id.overlay_container);
        previewOverlay = (ImageView) findViewById(R.id.preview_overlay);

        // Align the preview overlay
        observer = cameraPreview.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                int width = cameraPreview.getWidth();
                int height = cameraPreview.getHeight();
                int overlayWidth;
                int overlayHeight;

                if (overlayContainer.getLayoutParams().width != width ||
                        overlayContainer.getLayoutParams().height != height) {

                    // Make the overlay square
                    if (width < height) {
                        overlayWidth = width;
                        overlayHeight = width;
                    } else {
                        overlayWidth = height;
                        overlayHeight = width;
                    }

                    overlayContainer.getLayoutParams().width = width;
                    overlayContainer.getLayoutParams().height = height;
                    previewOverlay.getLayoutParams().width = overlayWidth;
                    previewOverlay.getLayoutParams().height = overlayHeight;
                    overlayContainer.requestLayout();
                }
            }
        });
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Prepare the neural network
        classifier.create(activity, activity.getAssets());
    }

    @Override
    public void onResume() {
        super.onResume();

        // Start the real-time classification thread
        startBackgroundThread();

        if (cameraPreview.isAvailable()) {
            // Start the camera
            initializeCamera(cameraPreview.getWidth(), cameraPreview.getHeight());
        } else {
            // Wait for the camera preview to become available
            cameraPreview.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        closeCamera();
        stopBackgroundThread();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        classifier.destroy();
    }
}
