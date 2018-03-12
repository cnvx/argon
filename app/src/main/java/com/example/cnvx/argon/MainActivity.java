package com.example.cnvx.argon;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
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
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_REQUEST_CODE = 1;

    // Maximum preview resolution supported by the camera2 API
    private static final int MAXIMUM_PREVIEW_WIDTH = 1920;
    private static final int MAXIMUM_PREVIEW_HEIGHT = 1080;

    private boolean resumed;
    private boolean startClassifying = false;
    private FitTextureView textureView;
    private TextView textView;
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

    // Whether or not the system UI should be auto-hidden after a delay
    private static final boolean AUTO_HIDE = true;
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    // Some older devices needs a small delay between UI updates
    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler hideHandler = new Handler();
    private final Runnable hidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar
            textureView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private final Runnable showPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
        }
    };
    private boolean visible;
    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    // Prevent the jarring behavior of controls going away
    private final View.OnTouchListener delayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (AUTO_HIDE) {
                delayedHide(AUTO_HIDE_DELAY_MILLIS);
            }
            return false;
        }
    };

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
            SurfaceTexture texture = textureView.getSurfaceTexture();

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
                    textView.setText(text);
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

                            // Get the optimal preview size

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
                                                outputSizes[index].getHeight() >= previewHeight)
                                            biggerThanPreview.add(outputSizes[index]);
                                        else
                                            smallerThanPreview.add(outputSizes[index]);
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

                            if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE)
                                textureView.setAspectRatio(finalPreviewResolution.getWidth(),
                                        finalPreviewResolution.getHeight());
                            else
                                textureView.setAspectRatio(finalPreviewResolution.getHeight(),
                                        finalPreviewResolution.getWidth());

                            previewResolution = finalPreviewResolution;
                            camera = id;
                            setPreviewTransform(width, height);

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

    // Set up the transform for the TextureView
    private void setPreviewTransform(int previewWidth, int previewHeight) {

        if (activity != null && previewResolution != null && textureView != null ) {

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

            textureView.setTransform(matrix);
        }
    }

    // Try to classify a single image from the camera
    private void classifyImage() {
        if (activity != null && classifier != null && cameraDevice != null) {

            // Use the TextureView as input, it's lower resolution but much faster
            Bitmap image = textureView.getBitmap();

            // Get the classification and show it
            displayText(classifier.classify(image));

            // Clear the memory for the next classification
            image.recycle();
        } else
            displayText("Initializing, please wait");
    }

    // Classify input from the camera asynchronously, to be run on the background thread
    private Runnable repeatedlyClassify =
        new Runnable() {

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
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;

            synchronized (lock) {
                startClassifying = false;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        visible = true;

        // Get the camera preview and label text
        textureView = findViewById(R.id.camera_preview);
        textView = findViewById(R.id.label_text);

        // Set up the user interaction to manually show or hide the system UI.
        textureView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // Trigger the initial hide() shortly after the activity has been created
        delayedHide(100);

        // Prepare the neural network
        classifier.create(activity, activity.getAssets());

        // Start the real-time classification thread
        startBackgroundThread();
    }

    @Override
    public void onResume() {
        super.onResume();

        startBackgroundThread();

        if (textureView.isAvailable()) {
            // Start the camera
            initializeCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            // Wait for the camera preview to become available
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }

        resumed = true;
    }

    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        resumed = false;

        super.onPause();
    }

    @Override
    public void onDestroy() {
        classifier.destroy();

        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);

        // Adjust preview on orientation change
        if (resumed) {
            stopBackgroundThread();
            closeCamera();
            onResume();
        }
    }

    private void toggle() {
        if (visible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        visible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        hideHandler.removeCallbacks(showPart2Runnable);
        hideHandler.postDelayed(hidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        textureView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        visible = true;

        // Schedule a runnable to display UI elements after a delay
        hideHandler.removeCallbacks(hidePart2Runnable);
        hideHandler.postDelayed(showPart2Runnable, UI_ANIMATION_DELAY);
    }

    // Schedules a call to hide() in delay milliseconds, canceling any previously scheduled calls
    private void delayedHide(int delayMillis) {
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, delayMillis);
    }
}
