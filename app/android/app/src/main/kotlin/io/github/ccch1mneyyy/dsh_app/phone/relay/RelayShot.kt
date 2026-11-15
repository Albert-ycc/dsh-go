package io.github.ccch1mneyyy.dsh_app.phone.relay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ElementKind
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ImageGeometry
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenElement
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A relay screenshot with the element numbers drawn on it, and how its pixels map back to the display. */
internal class RelayComposed(val bytes: ByteArray?, val geometry: ImageGeometry?, val secure: Boolean, val error: String?)

internal fun interface RelayShots {
    fun compose(jpeg: ByteArray, marks: List<ScreenElement>, secrets: List<Box>, screen: Box): RelayComposed
}

/**
 * Same picture as `a11y/ScreenShooter` makes (long side ≤ 1300 px, greyed password boxes,
 * numbered outlines, JPEG 80), from the JPEG the Mac relay sends instead of an accessibility
 * capture. The drawing is the second copy of ScreenShooter.draw; the third use earns a shared one.
 */
internal object AndroidRelayShots : RelayShots {
    override fun compose(jpeg: ByteArray, marks: List<ScreenElement>, secrets: List<Box>, screen: Box): RelayComposed {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            // l10n:keep agent screenshot result
            ?: return RelayComposed(null, null, false, "截图解码失败")
        try {
            // l10n:keep agent screenshot result
            if (looksBlack(decoded)) return RelayComposed(null, null, true, "截图全黑，可能是禁止截图的页面")
            val factor = minOf(1f, 1300f / max(decoded.width, decoded.height))
            val w = (decoded.width * factor).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * factor).roundToInt().coerceAtLeast(1)
            var scaled = if (factor < 1f) Bitmap.createScaledBitmap(decoded, w, h, true) else decoded
            if (scaled === decoded || !scaled.isMutable) {
                val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
                if (scaled !== decoded) scaled.recycle()
                scaled = mutable
            }
            try {
                val sx = w.toFloat() / screen.width.coerceAtLeast(1)
                val sy = h.toFloat() / screen.height.coerceAtLeast(1)
                draw(scaled, marks, secrets, screen, sx, sy)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                return RelayComposed(out.toByteArray(), ImageGeometry(screen.left, screen.top, sx, sy, w, h), false, null)
            } finally {
                scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun looksBlack(bitmap: Bitmap): Boolean {
        var dark = 0
        val n = 32
        for (i in 0 until n) for (j in 0 until n) {
            val c = bitmap.getPixel((bitmap.width - 1) * i / (n - 1), (bitmap.height - 1) * j / (n - 1))
            if (max(Color.red(c), max(Color.green(c), Color.blue(c))) < 12) dark++
        }
        return dark >= n * n * 97 / 100
    }

    private fun draw(bitmap: Bitmap, marks: List<ScreenElement>, secrets: List<Box>, origin: Box, sx: Float, sy: Float) {
        val canvas = Canvas(bitmap)
        val grey = Paint().apply { color = Color.rgb(128, 128, 128) }
        for (b in secrets) {
            canvas.drawRect((b.left - origin.left) * sx, (b.top - origin.top) * sy, (b.right - origin.left) * sx, (b.bottom - origin.top) * sy, grey)
        }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.argb(200, 61, 90, 152) }
        val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 33, 50, 98) }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.WHITE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }
        val metrics = text.fontMetrics
        for (e in marks) {
            if (e.kind == ElementKind.LIST) continue
            val r = RectF((e.bounds.left - origin.left) * sx, (e.bounds.top - origin.top) * sy, (e.bounds.right - origin.left) * sx, (e.bounds.bottom - origin.top) * sy)
            if (r.right <= 0 || r.bottom <= 0 || r.left >= bitmap.width || r.top >= bitmap.height) continue
            canvas.drawRect(r, outline)
            val label = e.id.toString()
            val tw = text.measureText(label) + 8f
            val th = metrics.descent - metrics.ascent + 2f
            val left = r.left.coerceIn(0f, bitmap.width - tw)
            val top = r.top.coerceIn(0f, bitmap.height - th)
            val box = RectF(left, top, left + tw, top + th)
            canvas.drawRoundRect(box, 4f, 4f, tag)
            canvas.drawRoundRect(box, 4f, 4f, halo)
            canvas.drawText(label, left + 4f, top + 1f - metrics.ascent, text)
        }
    }
}
