package com.example.cnvx.argon;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;
import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public class CobaltClassifier {

    private static final String NEURAL_NETWORK = "cobalt.pb";
    private static final String INPUT_NODE = "x:0";
    private static final String OUTPUT_NODE = "third_fully_connected_layer/output:0";
    private static final String[] OUTPUT_NODES = {OUTPUT_NODE};
    private static final int IMAGE_SIZE = 24;
    private static final float PROBABILITY_THRESHOLD = 0.90f;
    private static final List<String> LABELS = new ArrayList<String>() {{
        add("airplane");
        add("automobile");
        add("bird");
        add("cat");
        add("deer");
        add("dog");
        add("frog");
        add("horse");
        add("ship");
        add("truck");
    }};
    private float[] output = new float[LABELS.size()];
    private String prediction;

    private TensorFlowInferenceInterface inference;

    // Create a new TensorFlow session and load the neural network
    public void create(Activity activity, AssetManager manager) {
        try {
            inference = new TensorFlowInferenceInterface(manager, NEURAL_NETWORK);
        } catch (RuntimeException e) {
            Log.e("argon", "Could not find exported network " + NEURAL_NETWORK +
                    " in assets, is it called something else?");

            activity.finish();
        }
    }

    public void destroy() {
        inference.close();
        inference = null;
    }

    // Return a flattened (1 dimensional) array, to be reshaped to 24x24x3 later, with the following internal structure:
    // [R1, G1, B1, R2, G2, B2, R3, G3, B3, ...] where each element represents a single pixels red, green or blue value
    private float[] bitmapToFloatArray(Bitmap image) {
        float[] array = new float[image.getWidth() * image.getHeight() * 3];
        int index = 0;

        for (int y = 0; y < image.getWidth(); y++) {
            for (int x = 0; x < image.getHeight(); x++) {
                int colour = image.getPixel(x, y);

                array[index * 3] = Color.red(colour) / 255f;
                array[index * 3 + 1] = Color.green(colour) / 255f;
                array[index * 3 + 2] = Color.blue(colour) / 255f;

                index++;
            }
        }

        return array;
    }

    private Bitmap cropToSquare(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        Bitmap cropped;

        if (width >= height)
            cropped = Bitmap.createBitmap(bitmap, (width / 2) - (height / 2), 0, height, height);
        else
            cropped = Bitmap.createBitmap(bitmap, 0, (height / 2) - (width / 2), width, width);

        return cropped;
    }

    public String classify(Bitmap image) {
        // Make the image square
        Bitmap squaredImage = cropToSquare(image);

        int width = squaredImage.getWidth();
        int height = squaredImage.getHeight();
        float scaledWidth = ((float) IMAGE_SIZE) / width;
        float scaledHeight = ((float) IMAGE_SIZE) / height;

        Matrix matrix = new Matrix();
        matrix.postScale(scaledWidth, scaledHeight);

        // Lower the resolution to 24x24 pixels
        Bitmap scaledImage = Bitmap.createBitmap(squaredImage, 0, 0,
                squaredImage.getWidth(), squaredImage.getHeight(), matrix, false);

        // Feed a single image with 3 colour channels
        inference.feed(INPUT_NODE, bitmapToFloatArray(scaledImage), 1, IMAGE_SIZE, IMAGE_SIZE, 3);

        // Run the network
        inference.run(OUTPUT_NODES);

        // Get the classification
        inference.fetch(OUTPUT_NODE, output);

        float probability = output[0];
        int objectClass = 0;

        // Get the object class
        for (int index = 0; index < output.length; index++) {
            if (output[index] > probability) {
                probability = output[index];
                objectClass = index;
            }
        }

        if (probability >= PROBABILITY_THRESHOLD) {
            // Get the corresponding human readable label
            prediction = LABELS.get(objectClass);
        } else
            prediction = "";

        return prediction;
    }
}
