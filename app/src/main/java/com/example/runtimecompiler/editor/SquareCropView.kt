package com.example.runtimecompiler.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * High-performance 1:1 Square Crop View.
 * Supports:
 * - Fluid single-finger pan
 * - Continuous pinch-to-zoom without abrupt jumps upon gesture end
 * - 90° clockwise rotation
 * - Reset / Re-center frame
 * - Rule-of-Thirds 3x3 grid & corner handles
 * - Outside dark mask overlay
 * - Direct 1:1 sub-bitmap extraction scaled to 512x512
 */
class SquareCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var sourceBitmap: Bitmap? = null
    private val transformMatrix = Matrix()
    private val matrixValues = FloatArray(9)

    // Crop box rectangle (centered square in view)
    private val cropRect = RectF()

    // Visual Paints
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#B30F172A") // 70% dark mask outside crop box
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Accent sky blue border
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#59FFFFFF") // 35% translucent white rule-of-thirds grid
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8") // Accent blue corner brackets
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Multi-touch tracking
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor.isInfinite() || scaleFactor.isNaN()) return false

            transformMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
            checkBoundsAndConstrain()
            invalidate()
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            checkBoundsAndConstrain()
            invalidate()
        }
    })

    fun setImageBitmap(bmp: Bitmap) {
        this.sourceBitmap = bmp
        recenter()
    }

    /**
     * Resets the crop frame to center the entire image filled inside the 1:1 square.
     */
    fun recenter() {
        val bmp = sourceBitmap ?: return
        if (cropRect.width() <= 0f) return

        transformMatrix.reset()
        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        // Minimum scale to cover crop box completely
        val scale = maxOf(cropRect.width() / bmpW, cropRect.height() / bmpH)
        val scaledW = bmpW * scale
        val scaledH = bmpH * scale

        // Center within cropRect
        val dx = cropRect.left + (cropRect.width() - scaledW) / 2f
        val dy = cropRect.top + (cropRect.height() - scaledH) / 2f

        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(dx, dy)
        checkBoundsAndConstrain()
        invalidate()
    }

    /**
     * Rotates the image 90 degrees clockwise and refits it to the square crop box.
     */
    fun rotate90Degrees() {
        val bmp = sourceBitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        this.sourceBitmap = rotated
        recenter()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 16f * resources.displayMetrics.density
        val size = minOf(w - padding * 2, h - padding * 2).coerceAtLeast(100f)
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        cropRect.set(left, top, left + size, top + size)
        recenter()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Only pan with single pointer if pinch-to-zoom is not in progress
                if (!scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex != -1) {
                        val x = event.getX(pointerIndex)
                        val y = event.getY(pointerIndex)
                        val dx = x - lastTouchX
                        val dy = y - lastTouchY

                        transformMatrix.postTranslate(dx, dy)
                        checkBoundsAndConstrain()
                        invalidate()

                        lastTouchX = x
                        lastTouchY = y
                    }
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // When a second finger is placed, anchor the pointer to prevent jumps
                val actionIndex = event.actionIndex
                activePointerId = event.getPointerId(actionIndex)
                lastTouchX = event.getX(actionIndex)
                lastTouchY = event.getY(actionIndex)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // When a finger is lifted during multi-touch, transfer active pointer to remaining finger
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newPointerIndex)
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    private fun checkBoundsAndConstrain() {
        val bmp = sourceBitmap ?: return
        if (cropRect.width() <= 0f) return

        transformMatrix.getValues(matrixValues)
        val scaleX = matrixValues[Matrix.MSCALE_X]

        // Ensure minimum scale so cropRect is never exposed
        val minScale = maxOf(cropRect.width() / bmp.width, cropRect.height() / bmp.height)
        if (scaleX < minScale) {
            val rescale = minScale / scaleX
            transformMatrix.postScale(rescale, rescale, cropRect.centerX(), cropRect.centerY())
            checkBoundsAndConstrain()
            return
        }

        transformMatrix.getValues(matrixValues)
        val currentScaleX = matrixValues[Matrix.MSCALE_X]
        val currentScaleY = matrixValues[Matrix.MSCALE_Y]
        val currentTransX = matrixValues[Matrix.MTRANS_X]
        val currentTransY = matrixValues[Matrix.MTRANS_Y]

        val bmpW = bmp.width * currentScaleX
        val bmpH = bmp.height * currentScaleY

        var deltaX = 0f
        var deltaY = 0f

        val currentLeft = currentTransX
        val currentTop = currentTransY
        val currentRight = currentLeft + bmpW
        val currentBottom = currentTop + bmpH

        if (currentLeft > cropRect.left) {
            deltaX = cropRect.left - currentLeft
        } else if (currentRight < cropRect.right) {
            deltaX = cropRect.right - currentRight
        }

        if (currentTop > cropRect.top) {
            deltaY = cropRect.top - currentTop
        } else if (currentBottom < cropRect.bottom) {
            deltaY = cropRect.bottom - currentBottom
        }

        transformMatrix.postTranslate(deltaX, deltaY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = sourceBitmap ?: return

        // 1. Draw transformed bitmap
        canvas.drawBitmap(bmp, transformMatrix, bitmapPaint)

        // 2. Dim outer area with Inverse Even-Odd Path
        val path = Path().apply {
            fillType = Path.FillType.INVERSE_EVEN_ODD
            addRoundRect(cropRect, 16f, 16f, Path.Direction.CW)
        }
        canvas.drawPath(path, dimPaint)

        // 3. Draw 1:1 Crop Border
        canvas.drawRoundRect(cropRect, 16f, 16f, borderPaint)

        // 4. Draw 3x3 Rule-of-Thirds Grid Guidelines
        val cellW = cropRect.width() / 3f
        val cellH = cropRect.height() / 3f

        // Vertical lines
        canvas.drawLine(cropRect.left + cellW, cropRect.top, cropRect.left + cellW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + cellW * 2, cropRect.top, cropRect.left + cellW * 2, cropRect.bottom, gridPaint)

        // Horizontal lines
        canvas.drawLine(cropRect.left, cropRect.top + cellH, cropRect.right, cropRect.top + cellH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + cellH * 2, cropRect.right, cropRect.top + cellH * 2, gridPaint)

        // 5. Draw Corner Brackets
        val cornerLen = 22f * resources.displayMetrics.density
        // Top-Left
        canvas.drawLine(cropRect.left, cropRect.top + cornerLen, cropRect.left, cropRect.top + 8f, cornerPaint)
        canvas.drawLine(cropRect.left + 8f, cropRect.top, cropRect.left + cornerLen, cropRect.top, cornerPaint)
        // Top-Right
        canvas.drawLine(cropRect.right, cropRect.top + cornerLen, cropRect.right, cropRect.top + 8f, cornerPaint)
        canvas.drawLine(cropRect.right - 8f, cropRect.top, cropRect.right - cornerLen, cropRect.top, cornerPaint)
        // Bottom-Left
        canvas.drawLine(cropRect.left, cropRect.bottom - cornerLen, cropRect.left, cropRect.bottom - 8f, cornerPaint)
        canvas.drawLine(cropRect.left + 8f, cropRect.bottom, cropRect.left + cornerLen, cropRect.bottom, cornerPaint)
        // Bottom-Right
        canvas.drawLine(cropRect.right, cropRect.bottom - cornerLen, cropRect.right, cropRect.bottom - 8f, cornerPaint)
        canvas.drawLine(cropRect.right - 8f, cropRect.bottom, cropRect.right - cornerLen, cropRect.bottom, cornerPaint)
    }

    /**
     * Extracts the exact visible square bitmap from cropRect and scales it to targetSize x targetSize (e.g. 512x512).
     */
    fun cropToBitmap(targetSize: Int = 512): Bitmap? {
        val bmp = sourceBitmap ?: return null
        transformMatrix.getValues(matrixValues)

        val scaleX = matrixValues[Matrix.MSCALE_X]
        val scaleY = matrixValues[Matrix.MSCALE_Y]
        val transX = matrixValues[Matrix.MTRANS_X]
        val transY = matrixValues[Matrix.MTRANS_Y]

        // Calculate source rectangle in original bitmap coordinates
        val srcLeft = ((cropRect.left - transX) / scaleX).coerceIn(0f, bmp.width.toFloat())
        val srcTop = ((cropRect.top - transY) / scaleY).coerceIn(0f, bmp.height.toFloat())
        val srcRight = ((cropRect.right - transX) / scaleX).coerceIn(0f, bmp.width.toFloat())
        val srcBottom = ((cropRect.bottom - transY) / scaleY).coerceIn(0f, bmp.height.toFloat())

        val srcW = (srcRight - srcLeft).toInt().coerceAtLeast(1)
        val srcH = (srcBottom - srcTop).toInt().coerceAtLeast(1)

        val clampedW = minOf(srcW, bmp.width - srcLeft.toInt()).coerceAtLeast(1)
        val clampedH = minOf(srcH, bmp.height - srcTop.toInt()).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(bmp, srcLeft.toInt(), srcTop.toInt(), clampedW, clampedH)
        return Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
    }
}
