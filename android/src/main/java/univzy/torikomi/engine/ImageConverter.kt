package univzy.torikomi.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import java.io.File

/** Converts a downloaded photo to [format] (jpg/png/webp) via the platform codecs. */
object ImageConverter {
    val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")

    /** Returns the converted file (same dir/base name), or [src] itself if already in [format] or undecodable. */
    @Suppress("DEPRECATION") // WEBP is the only option below API 30
    fun convert(src: File, format: String): File {
        val ext = src.extension.lowercase()
        if (ext == format || (format == "jpg" && ext == "jpeg")) return src
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return src
        val dest = File(src.parentFile, "${src.nameWithoutExtension}.$format")
        try {
            val (fmt, flatten) = when (format) {
                "png" -> Bitmap.CompressFormat.PNG to false
                "webp" -> (if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                    else Bitmap.CompressFormat.WEBP) to false
                else -> Bitmap.CompressFormat.JPEG to true
            }
            // JPEG has no alpha: paint on white instead of getting a black background.
            val out = if (flatten && bmp.hasAlpha()) {
                Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, 0f, null) }
                }
            } else bmp
            dest.outputStream().use { out.compress(fmt, 95, it) }
        } catch (t: Throwable) {
            dest.delete()
            return src
        } finally {
            bmp.recycle()
        }
        src.delete()
        return dest
    }
}
