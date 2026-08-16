package com.example.runtimecompiler.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.ViewGroup
import android.widget.Toast
import com.example.runtimecompiler.R
import com.google.android.material.button.MaterialButton
import java.io.InputStream

/**
 * Isolated manager to safely load, auto-orient from EXIF,
 * present the cropping UI with Rotate/Recenter controls, and extract 1:1 512x512 app icons.
 */
object ImageCropManager {

    /**
     * Loads the bitmap from the given URI with EXIF orientation correction and bounds sampling,
     * then opens the interactive square crop dialog.
     */
    fun openCropper(
        context: Context,
        imageUri: Uri,
        onCropped: (Bitmap) -> Unit
    ) {
        val originalBitmap = decodeAndAutoOrient(context, imageUri)
        if (originalBitmap == null) {
            Toast.makeText(context, "Failed to load image from gallery", Toast.LENGTH_SHORT).show()
            return
        }

        showCropDialog(context, originalBitmap, onCropped)
    }

    private fun decodeAndAutoOrient(context: Context, uri: Uri): Bitmap? {
        return try {
            val contentResolver = context.contentResolver

            // 1. Determine dimensions and sample size to prevent OOM
            var inSample = 1
            contentResolver.openInputStream(uri)?.use { stream ->
                val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, boundsOptions)
                val maxDim = 2048
                while (boundsOptions.outWidth / inSample > maxDim || boundsOptions.outHeight / inSample > maxDim) {
                    inSample *= 2
                }
            }

            // 2. Decode sampled bitmap
            var bitmap: Bitmap? = null
            contentResolver.openInputStream(uri)?.use { stream ->
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = inSample }
                bitmap = BitmapFactory.decodeStream(stream, null, decodeOptions)
            }

            val bmp = bitmap ?: return null

            // 3. Read EXIF Orientation and auto-rotate if needed
            val orientation = getExifOrientation(context, uri)
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            } else {
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getExifOrientation(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream: InputStream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun showCropDialog(
        context: Context,
        bitmap: Bitmap,
        onCropped: (Bitmap) -> Unit
    ) {
        val dialog = Dialog(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
        dialog.setContentView(R.layout.dialog_crop_image)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val cropView = dialog.findViewById<SquareCropView>(R.id.crop_square_view)
        val btnRotate = dialog.findViewById<MaterialButton>(R.id.crop_btn_rotate)
        val btnRecenter = dialog.findViewById<MaterialButton>(R.id.crop_btn_recenter)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.crop_btn_cancel)
        val btnApply = dialog.findViewById<MaterialButton>(R.id.crop_btn_apply)

        cropView.setImageBitmap(bitmap)

        btnRotate.setOnClickListener {
            cropView.rotate90Degrees()
        }

        btnRecenter.setOnClickListener {
            cropView.recenter()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnApply.setOnClickListener {
            val cropped = cropView.cropToBitmap(512)
            if (cropped != null) {
                dialog.dismiss()
                onCropped(cropped)
            } else {
                Toast.makeText(context, "Failed to crop image", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }
}
