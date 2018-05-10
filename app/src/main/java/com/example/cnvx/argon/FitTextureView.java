package com.example.cnvx.argon;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class FitTextureView extends TextureView {

    private int mRatioWidth = 0;
    private int mRatioHeight = 0;
    private int newWidth;
    private int newHeight;

    public FitTextureView(Context context) {
        this(context, null);
    }

    public FitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    // Set the aspect ratio
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative");
        }
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (mRatioWidth == 0 || mRatioHeight == 0) {
            newWidth = width;
            newHeight = height;
        } else {
            if (width < height * mRatioWidth / mRatioHeight) {
                newWidth = width;
                newHeight = width * mRatioHeight / mRatioWidth;
            } else {
                newWidth = height * mRatioWidth / mRatioHeight;
                newHeight = height;
            }
        }

        setMeasuredDimension(newWidth, newHeight);
    }
}
