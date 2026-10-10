@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.sync.app.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

private val SyncBg = Color(0xFF08080D)
private val GlassSurface = Color(0xFF121212).copy(alpha = 0.4f)
private val SheetSurface = Color.White.copy(alpha = 0.08f)
private val PressSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)

object LiquidGlassHost {
    @JvmStatic
    fun install(host: ComposeView, webView: WebView, model: LiquidGlassModel) {
        host.setContent {
            SyncLiquidGlassRoot(webView, model)
        }
    }
}

@Composable
fun SyncLiquidGlassRoot(webView: WebView, model: LiquidGlassModel) {
    val backdrop = rememberLayerBackdrop {
        drawRect(SyncBg)
        drawContent()
    }
    var snapshot by remember { mutableStateOf<Bitmap?>(null) }
    val frame = model.frameVersion
    val regions = model.regions
    val density = LocalDensity.current

    DisposableEffect(webView) {
        val capture = WebViewBackdropCapture(webView) { bmp ->
            snapshot = bmp
            model.notifyBackdropFrame()
        }
        capture.start()
        onDispose { capture.stop() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .drawWithContent {
                    drawRect(SyncBg)
                    val bmp = snapshot
                    if (bmp != null && !bmp.isRecycled && frame >= 0) {
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
                        )
                    }
                }
        )

        AndroidView(
            factory = {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        val ordered = remember(regions) {
            regions.sortedBy {
                when (it.kind) {
                    GlassKind.Capsule -> 0
                    GlassKind.Pill -> 1
                    GlassKind.Sheet -> 2
                }
            }
        }
        ordered.forEach { region ->
            key(region.id) {
                NativeGlassSurface(
                    region = region,
                    backdrop = backdrop,
                    snapshot = snapshot,
                    webView = webView,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(region.left.roundToInt(), region.top.roundToInt()) }
                        .requiredSize(
                            width = with(density) { region.width.toDp() },
                            height = with(density) { region.height.toDp() }
                        )
                )
            }
        }
    }
}

@Composable
private fun NativeGlassSurface(
    region: GlassRegion,
    backdrop: Backdrop,
    snapshot: Bitmap?,
    webView: WebView,
    modifier: Modifier
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(region.pressed) {
        progress.animateTo(if (region.pressed) 1f else 0f, PressSpring)
    }
    val passTouches = Modifier.pointerInteropFilter { event ->
        event.offsetLocation(region.left, region.top)
        webView.dispatchTouchEvent(event)
        event.offsetLocation(-region.left, -region.top)
        true
    }
    val restoreChrome: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
        drawSnapshotChrome(snapshot, region)
    }

    when (region.kind) {
        GlassKind.Capsule -> {
            Box(
                modifier
                    .then(passTouches)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(8f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        shadow = { Shadow.Default },
                        layerBlock = {
                            val p = progress.value
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, p)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(GlassSurface) },
                        onDrawFront = restoreChrome
                    )
                    .pressSheen(progress.value)
            )
        }
        GlassKind.Pill -> {
            Box(
                modifier
                    .then(passTouches)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val p = progress.value
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(
                                refractionHeight = 10f.dp.toPx() * (0.35f + 0.65f * p),
                                refractionAmount = 14f.dp.toPx() * (0.35f + 0.65f * p),
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = 0.55f + 0.45f * progress.value)
                        },
                        shadow = {
                            Shadow(alpha = 0.35f + 0.65f * progress.value)
                        },
                        innerShadow = {
                            InnerShadow(
                                radius = 8f.dp * progress.value,
                                alpha = progress.value
                            )
                        },
                        layerBlock = {
                            val p = progress.value
                            val scale = lerp(1f, 78f / 56f, p)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = {
                            val p = progress.value
                            drawRect(Color.White.copy(alpha = 0.1f * (1f - p)))
                            drawRect(Color.Black.copy(alpha = 0.03f * p))
                        },
                        onDrawFront = restoreChrome
                    )
                    .pressSheen(progress.value)
            )
        }
        GlassKind.Sheet -> {
            val radiusDp = with(LocalDensity.current) { region.cornerRadius.toDp() }
            Box(
                modifier
                    .then(passTouches)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedRectangle(radiusDp) },
                        effects = {
                            vibrancy()
                            blur(4f.dp.toPx())
                            lens(24f.dp.toPx(), 48f.dp.toPx(), true)
                        },
                        highlight = { Highlight.Default },
                        shadow = { Shadow.Default },
                        layerBlock = {
                            val p = progress.value
                            val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, p)
                            scaleX = scale
                            scaleY = scale
                        },
                        onDrawSurface = { drawRect(SheetSurface) },
                        onDrawFront = restoreChrome
                    )
                    .pressSheen(progress.value)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSnapshotChrome(
    snapshot: Bitmap?,
    region: GlassRegion
) {
    if (snapshot == null || snapshot.isRecycled) return
    val left = region.left.roundToInt().coerceIn(0, snapshot.width)
    val top = region.top.roundToInt().coerceIn(0, snapshot.height)
    val width = region.width.roundToInt().coerceAtMost(snapshot.width - left).coerceAtLeast(1)
    val height = region.height.roundToInt().coerceAtMost(snapshot.height - top).coerceAtLeast(1)
    drawImage(
        image = snapshot.asImageBitmap(),
        srcOffset = IntOffset(left, top),
        srcSize = IntSize(width, height),
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        blendMode = BlendMode.Lighten
    )
}

private fun Modifier.pressSheen(progress: Float): Modifier {
    if (progress <= 0f) return this
    return drawWithContent {
        drawContent()
        drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
    }
}

private class WebViewBackdropCapture(
    private val webView: WebView,
    private val onFrame: (Bitmap) -> Unit
) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var copying = false
    private var lastNs = 0L
    private var back: Bitmap? = null
    private var front: Bitmap? = null

    fun start() {
        running = true
        choreographer.postFrameCallback(this)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(this)
        back = null
        front = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        choreographer.postFrameCallback(this)
        if (copying) return
        if (frameTimeNanos - lastNs < 40_000_000L) return
        lastNs = frameTimeNanos
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0 || !webView.isAttachedToWindow) return
        val bmp = obtainBack(w, h) ?: return
        copying = true
        copy(bmp)
    }

    private fun obtainBack(w: Int, h: Int): Bitmap? {
        val cur = back
        if (cur != null && !cur.isRecycled && cur.width == w && cur.height == h) return cur
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { back = it }
        } catch (_: OutOfMemoryError) {
            back = null
            null
        }
    }

    private fun publish(src: Bitmap) {
        val w = src.width
        val h = src.height
        var dst = front
        if (dst == null || dst.isRecycled || dst.width != w || dst.height != h) {
            dst = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return
            }
            front = dst
        }
        val c = Canvas(dst)
        c.drawBitmap(src, 0f, 0f, null)
        onFrame(dst)
    }

    private fun copy(bmp: Bitmap) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                PixelCopy.request(webView, null, bmp, { result ->
                    copying = false
                    if (result == PixelCopy.SUCCESS) publish(bmp)
                }, handler)
                return
            } catch (_: IllegalArgumentException) {
                // software fallback
            }
        }
        try {
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            webView.draw(Canvas(bmp))
            copying = false
            publish(bmp)
        } catch (_: Exception) {
            copying = false
        }
    }
}
