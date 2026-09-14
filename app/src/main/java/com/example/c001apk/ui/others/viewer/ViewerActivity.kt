package com.example.c001apk.ui.others.viewer

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.example.c001apk.R
import com.example.c001apk.constant.Constants.USER_AGENT
import com.example.c001apk.util.ImageUtil.saveOriginalToGallery
import com.example.c001apk.util.http2https
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.progressindicator.CircularProgressIndicator

class ViewerActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(
            WindowInsetsCompat.Type.statusBars()
        )
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        window.setBackgroundDrawableResource(android.R.color.black)

        val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: emptyList()
        val targets = intent.getStringArrayListExtra(EXTRA_TARGETS) ?: emptyList()
        if (urls.isEmpty()) {
            finish()
            return
        }

        viewPager = ViewPager2(this)
        viewPager.adapter = ViewerPageAdapter(urls, targets)
        viewPager.setCurrentItem(intent.getIntExtra(EXTRA_POSITION, 0), false)
        setContentView(
            viewPager,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    companion object {
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_TARGETS = "targets"
        private const val EXTRA_POSITION = "position"

        fun start(
            context: Context,
            urls: List<String>,
            targets: List<String>,
            position: Int
        ) {
            context.startActivity(
                Intent(context, ViewerActivity::class.java)
                    .putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
                    .putStringArrayListExtra(EXTRA_TARGETS, ArrayList(targets))
                    .putExtra(EXTRA_POSITION, position)
            )
        }
    }

    private class ViewerPageAdapter(
        private val urls: List<String>,
        private val targets: List<String>
    ) : RecyclerView.Adapter<ViewerPageHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewerPageHolder {
            val page = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_viewer_page, parent, false) as ConstraintLayout
            val photoView = page.findViewById<PhotoView>(R.id.photo_view)
            val progress = page.findViewById<CircularProgressIndicator>(R.id.progress)
            val retryText = page.findViewById<LinearLayout>(R.id.retry)
            val loadOriginal = page.findViewById<View>(R.id.load_original)
            return ViewerPageHolder(page, photoView, progress, retryText, loadOriginal)
        }

        override fun onBindViewHolder(holder: ViewerPageHolder, position: Int) {
            holder.bind(urls[position], targets.getOrNull(position))
        }

        override fun getItemCount(): Int = urls.size
    }

    private class ViewerPageHolder(
        private val page: ConstraintLayout,
        private val photoView: PhotoView,
        private val progress: CircularProgressIndicator,
        private val retryText: LinearLayout,
        private val loadOriginal: View
    ) : RecyclerView.ViewHolder(page) {

        private var boundUrl: String? = null
        private var targetUrl: String? = null
        private var displayedUrl: String? = null
        private val handler = Handler(Looper.getMainLooper())
        private var touchSlopSquare = 0
        private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f

        private var gesturesInitialized = false

        private fun setupGestures() {
            if (gesturesInitialized) return
            gesturesInitialized = true
            val touchSlop = ViewConfiguration.get(photoView.context).scaledTouchSlop
            touchSlopSquare = touchSlop * touchSlop
            retryText.setOnClickListener { loadInto(displayedUrl ?: boundUrl ?: return@setOnClickListener) }
            loadOriginal.setOnClickListener { loadInto(targetUrl ?: return@setOnClickListener) }
            photoView.setScaleType(ImageView.ScaleType.FIT_CENTER)
            photoView.setMinimumScale(1f)
            photoView.setMaximumScale(10f)
            photoView.setMediumScale(3f)
            photoView.setZoomable(true)
            photoView.setAllowParentInterceptOnEdge(false)
            photoView.setOnViewTapListener { _, _, _ ->
                (photoView.context as? AppCompatActivity)?.finish()
            }

            val longPressRunnable = Runnable { showSaveDialog() }
            page.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        handler.postDelayed(longPressRunnable, longPressTimeout)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.x - downX
                        val deltaY = event.y - downY
                        if (deltaX * deltaX + deltaY * deltaY > touchSlopSquare) {
                            handler.removeCallbacks(longPressRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPressRunnable)
                }
                view.performClick()
                false
            }
        }

        fun bind(url: String, target: String?) {
            setupGestures()
            boundUrl = url
            targetUrl = target
            displayedUrl = null
            photoView.setImageDrawable(null)
            photoView.scale = 1f
            retryText.isVisible = false
            loadOriginal.isVisible = false
            progress.isVisible = true
            loadInto(url)
        }

        private fun loadInto(requestUrl: String) {
            progress.isVisible = true
            retryText.isVisible = false
            val normalizedUrl = if (requestUrl.endsWith(".s.jpg")) {
                requestUrl.replace(".s.jpg", "")
            } else {
                requestUrl
            }
            val glideUrl = GlideUrl(
                normalizedUrl.http2https,
                LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT).build()
            )
            Glide.with(photoView)
                .load(glideUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(Target.SIZE_ORIGINAL)
                .transition(DrawableTransitionOptions.withCrossFade())
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        progress.isVisible = false
                        retryText.isVisible = true
                        return true
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        progress.isVisible = false
                        retryText.isVisible = false
                        photoView.scale = 1f
                        photoView.setImageDrawable(resource)
                        displayedUrl = normalizedUrl
                        loadOriginal.isVisible = targetUrl != null &&
                            targetUrl != normalizedUrl &&
                            (targetUrl.endsWith(".jpg") ||
                                targetUrl.endsWith(".jpeg") ||
                                targetUrl.endsWith(".png") ||
                                targetUrl.endsWith(".webp") ||
                                targetUrl.endsWith(".gif"))
                        return true
                    }
                })
                .into(photoView)
        }

        private fun showSaveDialog() {
            val saveUrl = targetUrl ?: displayedUrl ?: boundUrl ?: return
            MaterialAlertDialogBuilder(photoView.context)
                .setTitle("长按操作")
                .setItems(arrayOf("保存原图到相册")) { _, _ ->
                    saveOriginalToGallery(
                        photoView.context.applicationContext,
                        saveUrl
                    )
                }
                .show()
        }
    }
}
