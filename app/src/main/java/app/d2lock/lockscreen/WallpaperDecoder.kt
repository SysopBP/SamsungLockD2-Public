package app.d2lock.lockscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object WallpaperDecoder {
    /** Bound decoded pixels before allocation: ImageView.CENTER_CROP alone does not do this. */
    fun decode(context: Context, uri: Uri): Bitmap {
        val metrics = context.resources.displayMetrics
        val displayEdge = max(metrics.widthPixels, metrics.heightPixels).coerceIn(1, 3072)
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val scale = min(1.0, displayEdge.toDouble() / max(info.size.width, info.size.height))
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1)
            )
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        }
    }
}
