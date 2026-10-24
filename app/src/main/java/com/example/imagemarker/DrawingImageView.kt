package com.example.imagemarker

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val imageMatrix = Matrix()
    private val inverseMatrix = Matrix()

    // 绘制相关
    private val paths = mutableListOf<Path>()
    private var currentPath: Path? = null

    // 画线模式：自由画线 / 两点画线
    private var drawMode = DrawMode.FREE
    // 两点画线模式下已选中的起点（图片坐标）
    private var lineStartPoint: PointF? = null
    // 两点画线模式下跟随手指的预览终点（图片坐标）
    private var linePreviewPoint: PointF? = null

    private val drawPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    // 两点画线的起点标记画笔
    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }

    // 手势处理
    private var touchMode = TouchMode.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // 缩放因子（固定，不再支持缩放）
    private var scaleFactor = 1f

    // 平移
    private var translateX = 0f
    private var translateY = 0f

    private enum class TouchMode {
        NONE, DRAG, DRAW, LINE
    }

    enum class DrawMode {
        FREE, LINE
    }

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        paths.clear()
        resetLineState()
        resetTransform()
        invalidate()
    }

    fun setDrawMode(mode: DrawMode) {
        if (drawMode == mode) return
        drawMode = mode
        currentPath = null
        resetLineState()
        touchMode = TouchMode.NONE
        invalidate()
    }

    fun getDrawMode(): DrawMode = drawMode

    private fun resetLineState() {
        lineStartPoint = null
        linePreviewPoint = null
    }

    fun hasImage(): Boolean = bitmap != null

    fun hasPaths(): Boolean = paths.isNotEmpty()

    /**
     * 将当前标注烘焙到图片中，然后清空路径
     */
    fun bakeAnnotationsToImage(): Boolean {
        if (paths.isEmpty()) return false

        bitmap?.let { original ->
            // 创建可修改的副本
            val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)

            // 绘制所有路径到图片上
            val savePaint = Paint(drawPaint).apply {
                // 根据图片实际尺寸调整线宽
                strokeWidth = 3f * (original.width.toFloat() / (width / scaleFactor))
            }

            for (path in paths) {
                canvas.drawPath(path, savePaint)
            }

            // 更新bitmap为带标注的版本
            bitmap = mutableBitmap

            // 清空路径（因为已经烘焙到图片中了）
            paths.clear()
            invalidate()

            return true
        }
        return false
    }

    /**
     * 获取当前图片（可能已包含之前保存的标注）
     */
    fun getCurrentBitmap(): Bitmap? = bitmap

    private fun resetTransform() {
        bitmap?.let { bmp ->
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()

            if (viewWidth <= 0 || viewHeight <= 0) {
                post { resetTransform() }
                return
            }

            val bmpWidth = bmp.width.toFloat()
            val bmpHeight = bmp.height.toFloat()

            // 将图片适配到视图宽度（固定缩放比例）
            scaleFactor = viewWidth / bmpWidth

            translateX = 0f
            translateY = 0f

            updateMatrix()
        }
    }

    private fun updateMatrix() {
        imageMatrix.reset()
        imageMatrix.postScale(scaleFactor, scaleFactor)
        imageMatrix.postTranslate(translateX, translateY)
        imageMatrix.invert(inverseMatrix)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmap != null) {
            resetTransform()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let { bmp ->
            canvas.save()
            canvas.concat(imageMatrix)
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)

            // 绘制所有已保存的路径
            for (path in paths) {
                canvas.drawPath(path, drawPaint)
            }

            // 绘制当前路径
            currentPath?.let {
                canvas.drawPath(it, drawPaint)
            }

            // 两点画线：起点标记与预览线
            lineStartPoint?.let { start ->
                val pointRadius = 3f * resources.displayMetrics.density / scaleFactor
                canvas.drawCircle(start.x, start.y, pointRadius, pointPaint)
                linePreviewPoint?.let { end ->
                    canvas.drawLine(start.x, start.y, end.x, end.y, drawPaint)
                }
            }

            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (drawMode == DrawMode.LINE) {
                    // 两点画线：记录按下位置，已有起点时显示预览线
                    touchMode = TouchMode.LINE
                    if (lineStartPoint != null) {
                        val point = screenToImageCoords(event.x, event.y)
                        linePreviewPoint = PointF(point[0], point[1])
                        invalidate()
                    }
                } else {
                    // 单指：开始画线
                    touchMode = TouchMode.DRAW
                    startDrawing(event.x, event.y)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指按下：取消画线，切换到移动模式
                if (touchMode == TouchMode.DRAW) {
                    currentPath = null
                }
                linePreviewPoint = null
                touchMode = TouchMode.DRAG
                // 记录两指中点
                lastTouchX = (event.getX(0) + event.getX(1)) / 2f
                lastTouchY = (event.getY(0) + event.getY(1)) / 2f
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (touchMode == TouchMode.DRAW && event.pointerCount == 1) {
                    // 单指移动：画线
                    continueDrawing(event.x, event.y)
                } else if (touchMode == TouchMode.LINE && event.pointerCount == 1 && linePreviewPoint != null) {
                    // 两点画线：预览终点跟随手指
                    val point = screenToImageCoords(event.x, event.y)
                    linePreviewPoint = PointF(point[0], point[1])
                    invalidate()
                } else if (touchMode == TouchMode.DRAG && event.pointerCount >= 2) {
                    // 双指移动：移动图片
                    val centerX = (event.getX(0) + event.getX(1)) / 2f
                    val centerY = (event.getY(0) + event.getY(1)) / 2f

                    val dx = centerX - lastTouchX
                    val dy = centerY - lastTouchY

                    translateX += dx
                    translateY += dy

                    constrainTranslation()
                    updateMatrix()

                    lastTouchX = centerX
                    lastTouchY = centerY
                }
            }

            MotionEvent.ACTION_UP -> {
                when (touchMode) {
                    TouchMode.DRAW -> finishDrawing()
                    TouchMode.LINE -> handleLineTap(event.x, event.y)
                    else -> {}
                }
                touchMode = TouchMode.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (touchMode == TouchMode.DRAW) {
                    currentPath = null
                }
                linePreviewPoint = null
                touchMode = TouchMode.NONE
                invalidate()
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 一根手指抬起，保持移动模式直到全部抬起
                if (event.pointerCount == 2) {
                    // 只剩一根手指，不做任何操作，等待 ACTION_UP
                    touchMode = TouchMode.NONE
                }
            }
        }

        return true
    }

    private fun startDrawing(x: Float, y: Float) {
        val imagePoint = screenToImageCoords(x, y)
        currentPath = Path().apply {
            moveTo(imagePoint[0], imagePoint[1])
        }
        invalidate()
    }

    private fun continueDrawing(x: Float, y: Float) {
        currentPath?.let { path ->
            val imagePoint = screenToImageCoords(x, y)
            path.lineTo(imagePoint[0], imagePoint[1])
            invalidate()
        }
    }

    /**
     * 两点画线：第一次点击记录起点，第二次点击连成直线
     */
    private fun handleLineTap(x: Float, y: Float) {
        linePreviewPoint = null
        val bmp = bitmap ?: return

        val point = screenToImageCoords(x, y)
        // 只接受落在图片范围内的点
        if (point[0] < 0f || point[1] < 0f ||
            point[0] > bmp.width.toFloat() || point[1] > bmp.height.toFloat()
        ) {
            invalidate()
            return
        }

        val start = lineStartPoint
        if (start == null) {
            lineStartPoint = PointF(point[0], point[1])
        } else {
            val dx = point[0] - start.x
            val dy = point[1] - start.y
            // 忽略两次点击几乎重合的情况
            if (dx * dx + dy * dy >= 1f) {
                paths.add(Path().apply {
                    moveTo(start.x, start.y)
                    lineTo(point[0], point[1])
                })
            }
            lineStartPoint = null
        }
        invalidate()
    }

    private fun finishDrawing() {
        currentPath?.let { path ->
            paths.add(path)
            currentPath = null
            invalidate()
        }
    }

    private fun screenToImageCoords(x: Float, y: Float): FloatArray {
        val point = floatArrayOf(x, y)
        inverseMatrix.mapPoints(point)
        return point
    }

    private fun constrainTranslation() {
        bitmap?.let { bmp ->
            val scaledWidth = bmp.width * scaleFactor
            val scaledHeight = bmp.height * scaleFactor

            // 图片边缘不能超出视图边缘
            // 左边缘不能超出视图左边，右边缘不能超出视图右边
            if (scaledWidth <= width) {
                // 图片宽度小于视图宽度时，居中显示
                translateX = (width - scaledWidth) / 2f
            } else {
                // 图片宽度大于视图宽度时，限制边缘
                val minTranslateX = width - scaledWidth  // 右边缘对齐视图右边
                val maxTranslateX = 0f                    // 左边缘对齐视图左边
                translateX = translateX.coerceIn(minTranslateX, maxTranslateX)
            }

            // 上边缘不能超出视图上边，下边缘不能超出视图下边
            if (scaledHeight <= height) {
                // 图片高度小于视图高度时，居中显示
                translateY = (height - scaledHeight) / 2f
            } else {
                // 图片高度大于视图高度时，限制边缘
                val minTranslateY = height - scaledHeight  // 下边缘对齐视图下边
                val maxTranslateY = 0f                      // 上边缘对齐视图上边
                translateY = translateY.coerceIn(minTranslateY, maxTranslateY)
            }
        }
    }

    fun undo(): Boolean {
        // 两点画线模式下，先取消已选中的起点
        if (lineStartPoint != null) {
            resetLineState()
            invalidate()
            return true
        }
        if (paths.isNotEmpty()) {
            paths.removeAt(paths.size - 1)
            invalidate()
            return true
        }
        return false
    }

    fun clearAll() {
        paths.clear()
        resetLineState()
        invalidate()
    }

    fun clearImage() {
        bitmap = null
        paths.clear()
        currentPath = null
        resetLineState()
        invalidate()
    }

}
