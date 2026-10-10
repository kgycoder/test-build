package com.sync.app.liquidglass

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

enum class GlassKind {
    Capsule,
    Pill,
    Sheet
}

data class GlassRegion(
    val id: String,
    val kind: GlassKind,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cornerRadius: Float,
    val pressed: Boolean
)

class LiquidGlassModel {
    var regions by mutableStateOf<List<GlassRegion>>(emptyList())
        private set

    var frameVersion by mutableIntStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())

    fun notifyBackdropFrame() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            frameVersion++
        } else {
            main.post { frameVersion++ }
        }
    }

    fun updateFromJson(json: String) {
        val parsed = parse(json) ?: return
        main.post {
            if (parsed != regions) regions = parsed
        }
    }

    private fun parse(json: String): List<GlassRegion>? {
        return try {
            val root = JSONObject(json)
            val cssW = root.optDouble("vw", 0.0)
            val viewW = root.optDouble("aw", cssW)
            val scale = if (cssW > 0.0) (viewW / cssW).toFloat() else 1f
            val arr = root.optJSONArray("items") ?: return emptyList()
            val out = ArrayList<GlassRegion>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optDouble("w").toFloat() * scale
                val h = o.optDouble("h").toFloat() * scale
                if (w < 2f || h < 2f) continue
                out.add(
                    GlassRegion(
                        id = o.optString("id"),
                        kind = when (o.optString("k")) {
                            "pill" -> GlassKind.Pill
                            "sheet" -> GlassKind.Sheet
                            else -> GlassKind.Capsule
                        },
                        left = o.optDouble("l").toFloat() * scale,
                        top = o.optDouble("t").toFloat() * scale,
                        width = w,
                        height = h,
                        cornerRadius = o.optDouble("r", 24.0).toFloat() * scale,
                        pressed = o.optBoolean("p", false)
                    )
                )
            }
            out
        } catch (_: Exception) {
            null
        }
    }
}
