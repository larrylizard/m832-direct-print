package com.m832print.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.m832print.app.databinding.ActivityCropBinding

/**
 * Full-screen cropping screen -- 2026-10-06 request: cropping inline in
 * MainActivity's small 200dp preview was too imprecise, and that layout
 * had no room for an always-visible apply/cancel row once a tall image
 * was loaded.
 *
 * The bitmap being cropped is passed via the static pendingBitmap field
 * rather than through the launching Intent: Bundles have a transaction
 * size limit a real printed page can easily exceed, and both activities
 * run in the same process, so a plain in-memory handoff is simpler and
 * safer than round-tripping the actual pixels through Parcel. Only the
 * resulting normalized crop rect (4 floats) goes back through the
 * activity result Intent -- MainActivity already holds the original
 * bitmap(s) and does the actual cropping itself.
 */
class CropActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCropBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bitmap = pendingBitmap
        if (bitmap == null) {
            finish()
            return
        }
        binding.cropImage.setImageBitmap(bitmap)
        // imageMatrix isn't finalized until after this layout pass completes.
        binding.cropImage.post {
            val imageRect = computeDisplayedImageRect(binding.cropImage)
            val lp = binding.cropOverlay.layoutParams as FrameLayout.LayoutParams
            lp.width = imageRect.width().toInt().coerceAtLeast(1)
            lp.height = imageRect.height().toInt().coerceAtLeast(1)
            lp.leftMargin = imageRect.left.toInt()
            lp.topMargin = imageRect.top.toInt()
            binding.cropOverlay.layoutParams = lp
            binding.cropOverlay.reset()
        }

        binding.applyCropButton.setOnClickListener {
            val rect = binding.cropOverlay.getNormalizedRect()
            val data = Intent()
                .putExtra(EXTRA_LEFT, rect.left)
                .putExtra(EXTRA_TOP, rect.top)
                .putExtra(EXTRA_RIGHT, rect.right)
                .putExtra(EXTRA_BOTTOM, rect.bottom)
            setResult(RESULT_OK, data)
            finish()
        }
        binding.cancelCropButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingBitmap = null
    }

    companion object {
        var pendingBitmap: Bitmap? = null
        const val EXTRA_LEFT = "left"
        const val EXTRA_TOP = "top"
        const val EXTRA_RIGHT = "right"
        const val EXTRA_BOTTOM = "bottom"
    }
}
