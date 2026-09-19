package app.d2lock

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.d2lock.lockscreen.LockScreenActivity
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.drawable.BitmapDrawable
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull

@RunWith(AndroidJUnit4::class)
class PreviewSmokeTest {
    @Test fun previewWithLargeWallpaperUsesBoundedBitmap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "large-wallpaper.png")
        writeLargePng(image)
        Prefs.setWallpaper(context, Uri.fromFile(image).toString())
        val intent = Intent(context, LockScreenActivity::class.java).putExtra("preview", true)
        ActivityScenario.launch<LockScreenActivity>(intent).use { scenario ->
            SystemClock.sleep(4000)
            scenario.onActivity { activity ->
                fun findImage(view: View): ImageView? {
                    if (view is ImageView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) {
                        findImage(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                val bitmap = (findImage(activity.window.decorView)?.drawable as? BitmapDrawable)?.bitmap
                assertNotNull("Wallpaper should render", bitmap)
                assertTrue("Wallpaper must be decoded before rendering at a safe size", bitmap!!.byteCount <= 40 * 1024 * 1024)
            }
            scenario.recreate()
            SystemClock.sleep(4000)
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
        image.delete()
    }

    private fun writeLargePng(file: File) {
        // 36-megapixel fixture, generated without allocating its 144 MB decoded pixels.
        fun chunk(out: DataOutputStream, type: String, bytes: ByteArray) {
            val tag = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(tag); update(bytes) }
            out.writeInt(bytes.size); out.write(tag); out.write(bytes); out.writeInt(crc.value.toInt())
        }
        DataOutputStream(file.outputStream()).use { out ->
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            val header = ByteArrayOutputStream()
            DataOutputStream(header).apply { writeInt(6000); writeInt(6000); write(byteArrayOf(8,6,0,0,0)); flush() }
            chunk(out,"IHDR",header.toByteArray())
            val compressed = ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { deflater ->
                val row = ByteArray(6000 * 4 + 1)
                for (i in 4 until row.size step 4) row[i] = 255.toByte()
                repeat(6000) { deflater.write(row) }
            }
            chunk(out,"IDAT",compressed.toByteArray()); chunk(out,"IEND",byteArrayOf())
        }
    }

    @Test fun previewStartsAndRecreatesWithoutOptionalPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("lock_preferences", 0).edit().clear().commit()
        val intent = Intent(context, LockScreenActivity::class.java).putExtra("preview", true)
        ActivityScenario.launch<LockScreenActivity>(intent).use { scenario ->
            SystemClock.sleep(2200)
            scenario.onActivity { assertFalse(it.isFinishing) }
            scenario.recreate()
            SystemClock.sleep(1200)
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
