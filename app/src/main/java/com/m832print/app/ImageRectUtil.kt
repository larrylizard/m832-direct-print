package com.m832print.app

import android.graphics.RectF
import android.widget.ImageView

/**
 * The actual on-screen rect, in imageView-local pixel coordinates, where
 * a fitCenter-scaled ImageView draws its image -- accounts for any
 * letterboxing when the view's aspect ratio doesn't match the bitmap's.
 * Shared by MainActivity's inline preview and CropActivity's full-screen
 * one, both of which need to position a CropOverlayView exactly over the
 * visible image rather than the whole view.
 */
fun computeDisplayedImageRect(imageView: ImageView): RectF {
    val drawable = imageView.drawable
    if (drawable == null || imageView.width == 0 || imageView.height == 0) {
        return RectF(0f, 0f, imageView.width.toFloat(), imageView.height.toFloat())
    }
    val rect = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
    imageView.imageMatrix.mapRect(rect)
    return rect
}
