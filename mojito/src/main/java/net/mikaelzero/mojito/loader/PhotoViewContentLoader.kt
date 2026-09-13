package net.mikaelzero.mojito.loader

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.github.chrisbanes.photoview.PhotoView
import net.mikaelzero.mojito.interfaces.OnMojitoViewCallback

class PhotoViewContentLoader : ContentLoader {

    private lateinit var photoView: PhotoView
    private var isLongHeightImage = false
    private var isLongWidthImage = false
    private var hasDrawable = false
    private var onTapCallback: OnTapCallback? = null
    private var onLongTapCallback: OnLongTapCallback? = null

    override fun init(
        context: Context,
        originUrl: String,
        targetUrl: String?,
        onMojitoViewCallback: OnMojitoViewCallback?
    ) {
        photoView = PhotoView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setScaleLevels(1f, 4f, 8f)
            tag = this@PhotoViewContentLoader
            setOnLongClickListener { view ->
                onLongTapCallback?.let { callback ->
                    callback.onLongTap(view, 0f, 0f)
                    true
                } ?: false
            }
            setOnViewTapListener { view, x, y ->
                onTapCallback?.let { callback ->
                    callback.onTap(view, x, y)
                }
            }
        }
        photoView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    override fun providerView(): View = photoView

    override fun providerRealView(): View = photoView

    override val displayRect: RectF
        get() = photoView.displayRect ?: RectF(photoView.left.toFloat(), photoView.top.toFloat(), photoView.right.toFloat(), photoView.bottom.toFloat())

    override fun dispatchTouchEvent(
        isDrag: Boolean,
        isActionUp: Boolean,
        isDown: Boolean,
        isHorizontal: Boolean
    ): Boolean {
        if (isDrag || isActionUp) return false
        if (!hasDrawable) return false
        val isMin = photoView.scale <= photoView.minimumScale + SCALE_EPSILON
        return !(isMin && !isDown && !isHorizontal)
    }

    override fun dragging(width: Int, height: Int, ratio: Float) {}

    override fun beginBackToMin(isResetSize: Boolean) {
        if (isResetSize) {
            photoView.scale = photoView.minimumScale
        }
    }

    override fun backToNormal() {}

    override fun loadAnimFinish() {}

    override fun needReBuildSize(): Boolean =
        photoView.scale > photoView.minimumScale + SCALE_EPSILON

    override fun useTransitionApi(): Boolean =
        isLongHeightImage || isLongWidthImage || needReBuildSize()

    override fun isLongImage(width: Int, height: Int): Boolean {
        isLongHeightImage = height > width * 22f / 9f
        isLongWidthImage = width > height * 5 && width > photoView.resources.displayMetrics.widthPixels * 1.5
        return isLongHeightImage || isLongWidthImage
    }

    override fun onTapCallback(onTapCallback: OnTapCallback) {
        this.onTapCallback = onTapCallback
    }

    override fun onLongTapCallback(onLongTapCallback: OnLongTapCallback) {
        this.onLongTapCallback = onLongTapCallback
    }

    override fun pageChange(isHidden: Boolean) {}

    fun loadUri(uri: Uri) {
        val path = when (uri.scheme) {
            null, "", "file" -> uri.path ?: uri.toString()
            else -> uri.toString()
        }
        val drawable = decodeDrawable(path) ?: return
        hasDrawable = true
        photoView.setImageDrawable(drawable)
    }

    fun loadFail(drawableResId: Int) {
        photoView.setImageResource(drawableResId)
        hasDrawable = photoView.drawable != null
    }

    private fun decodeDrawable(path: String): Drawable? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, options)
        var sampleSize = 1
        val maxDimension = maxOf(options.outWidth, options.outHeight)
        while (maxDimension / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(path, decodeOptions)?.let { bitmap ->
            BitmapDrawable(null, bitmap)
        }
    }

    private companion object {
        const val SCALE_EPSILON = 0.01f
        const val MAX_DECODE_DIMENSION = 4096
    }
}
