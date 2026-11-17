package org.lbalab.lba2cc.ds

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.lbalab.lba2cc.DsLink
import android.graphics.BitmapFactory
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pictures the engine drew for the panel (SOURCES/DS_BRIDGE.CPP): the game's
 * font, its HUD sprites and the inventory's item models. Nothing of the game
 * is in the APK; all of it comes from the player's own data at run time.
 */
@Immutable
class GameAssets(
    val generation: Int,
    private val images: Map<Int, ImageBitmap>,
    val fontHeight: Int,
) {
    fun glyph(c: Char): ImageBitmap? = if (c.code in 32..126) images[GLYPH + c.code - 32] else null
    fun sprite(i: Int): ImageBitmap? = images[SPRITE + i]
    fun item(flag: Int): ImageBitmap? = images[ITEM + flag]
    val hasFont get() = fontHeight > 0 && glyph('A') != null

    companion object {
        const val GLYPH = 0
        const val SPRITE = 100
        const val ITEM = 200

        const val S_LIFE = 0
        const val S_MAGIC = 1
        const val S_KASHES = 2
        const val S_ZLITOS = 3
        const val S_KEY = 4
        const val S_CLOVER_BOX = 5
        const val S_CLOVER_BOX_FULL = 6

        val EMPTY = GameAssets(-1, emptyMap(), 0)

        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(256 * 256 * 4).order(ByteOrder.nativeOrder())
        private val ints = IntArray(256 * 256)

        /**
         * Reads every asset the engine has; called again when its generation moves.
         *
         * The item models are only drawn from the inventory's loop, so they are
         * also kept in [cache] (app-private storage, never shipped) and shown
         * from there until the inventory is next opened.
         */
        fun load(generation: Int, fontHeight: Int, cache: File): GameAssets {
            val out = HashMap<Int, ImageBitmap>()
            val size = IntArray(2)
            cache.mkdirs()
            for (id in (0 until 95) + (100 until 108) + (200 until 240)) {
                val cached = File(cache, "item-$id.png")
                if (DsLink.asset(id, buffer, size) == 0) {
                    if (id >= ITEM && cached.exists())
                        runCatching { BitmapFactory.decodeFile(cached.path) }.getOrNull()?.let { out[id] = it.asImageBitmap() }
                    continue
                }
                val (w, h) = size[0] to size[1]
                if (w <= 0 || h <= 0 || w * h > ints.size) continue
                buffer.rewind()
                buffer.asIntBuffer().get(ints, 0, w * h)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(ints, 0, w, 0, 0, w, h)
                out[id] = bmp.asImageBitmap()
                if (id >= ITEM) runCatching {
                    cached.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            return GameAssets(generation, out, fontHeight)
        }

        /**
         * The swirl of blue the game fills its selected menu entry with, made
         * here from sines rather than taken from the game.
         */
        val plasma: ImageBitmap by lazy {
            val w = 160
            val h = 48
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                val fx = x / w.toDouble()
                val fy = y / h.toDouble()
                val v = (sin(fx * 7.0 + 1.3) + sin(fy * 5.0 + fx * 3.0) + sin((fx + fy) * 9.0) +
                    sin(fx * 13.0 - fy * 4.0 + PI / 3)) / 4.0
                val t = ((v + 1.0) / 2.0).coerceIn(0.0, 1.0)
                val r = (8 + 40 * t).toInt()
                val g = (30 + 120 * t * t).toInt()
                val b = (60 + 150 * t).toInt()
                px[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}
