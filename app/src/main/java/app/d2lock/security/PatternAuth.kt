package app.d2lock.security

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.AtomicFile
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.d2lock.Appearance
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.hypot

class PatternStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "d2-pattern.json"))
    companion object { private val guard = Any(); private const val ITERATIONS = 210_000 }
    fun configured() = synchronized(guard) { file.baseFile.exists() || File(file.baseFile.path + ".bak").exists() }
    private fun read() = JSONObject(file.openRead().bufferedReader().use { it.readText() })
    private fun write(value: JSONObject) {
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun hash(pattern: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pattern, salt, ITERATIONS, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    fun create(nodes: List<Int>) = synchronized(guard) {
        require(!configured()) { "Pattern already configured" }
        require(nodes.distinct().size == nodes.size && nodes.size >= 4)
        val chars = nodes.joinToString("-").toCharArray()
        try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            write(JSONObject().put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .put("hash", Base64.encodeToString(hash(chars, salt), Base64.NO_WRAP))
                .put("failures", 0).put("until", 0L))
        } finally { chars.fill('\u0000') }
    }
    fun verify(nodes: List<Int>): Boolean = synchronized(guard) {
        val state = read()
        if (remaining(state) > 0) return@synchronized false
        val chars = nodes.joinToString("-").toCharArray()
        val valid = try {
            nodes.distinct().size == nodes.size && nodes.size >= 4 && MessageDigest.isEqual(
                Base64.decode(state.getString("hash"), Base64.NO_WRAP),
                hash(chars, Base64.decode(state.getString("salt"), Base64.NO_WRAP)))
        } finally { chars.fill('\u0000') }
        if (valid) state.put("failures", 0).put("until", 0L)
        else {
            val failures = (state.optInt("failures") + 1).coerceAtMost(100)
            state.put("failures", failures)
            if (failures >= 5) state.put("until", now() + (30_000L shl ((failures - 5) / 5).coerceAtMost(5)))
        }
        write(state)
        valid
    }
    private fun remaining(state: JSONObject) = (state.optLong("until") - now()).coerceAtLeast(0L)
    fun remainingMillis() = synchronized(guard) { remaining(read()) }
}

private class PatternView(context: Context) : View(context) {
    private val selected = mutableListOf<Int>()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    var hideTrail = false
    var onComplete: ((List<Int>) -> Unit)? = null
    private var fingerX = 0f
    private var fingerY = 0f

    init { minimumHeight = (300 * resources.displayMetrics.density).toInt(); filterTouchesWhenObscured = true }

    private fun point(index: Int): Pair<Float, Float> {
        val col = index % 3
        val row = index / 3
        val pad = width * .18f
        val gapX = (width - pad * 2) / 2f
        val gapY = (height - pad * 2) / 2f
        return (pad + col * gapX) to (pad + row * gapY)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val accent = Appearance.accent(context)
        val density = resources.displayMetrics.density
        val selectedSet = selected.toHashSet()
        lineGlow.color = Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent))
        lineGlow.strokeWidth = 12f * density
        line.color = Color.argb(190, Color.red(accent), Color.green(accent), Color.blue(accent))
        line.strokeWidth = 4f * density
        if (!hideTrail && selected.isNotEmpty()) {
            val path = Path()
            selected.forEachIndexed { i, n ->
                val (x,y)=point(n); if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            path.lineTo(fingerX, fingerY)
            canvas.drawPath(path, lineGlow)
            canvas.drawPath(path, line)
        }
        for (i in 0..8) {
            val (x,y)=point(i)
            val active = i in selectedSet
            val radius = (if (active) 18f else 15f) * density
            // Layered translucent node: glass fill, fine rim, and a small specular highlight.
            dot.color = if (active)
                Color.argb(105, Color.red(accent), Color.green(accent), Color.blue(accent))
            else Color.argb(34, 255, 255, 255)
            canvas.drawCircle(x, y, radius, dot)

            rim.strokeWidth = 1.15f * density
            rim.color = if (active)
                Color.argb(220, Color.red(accent), Color.green(accent), Color.blue(accent))
            else Color.argb(145, 255, 255, 255)
            canvas.drawCircle(x, y, radius, rim)

            highlight.color = Color.argb(if (active) 180 else 125, 255, 255, 255)
            canvas.drawCircle(x - radius * .28f, y - radius * .30f, radius * .18f, highlight)
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if ((event.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0) return false
        fingerX=event.x; fingerY=event.y
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                for(i in 0..8) {
                    if(i in selected) continue
                    val (x,y)=point(i)
                    if(hypot(event.x-x,event.y-y) < width*.11f) {
                        selected += i
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val result=selected.toList()
                selected.clear(); invalidate()
                onComplete?.invoke(result)
            }
            MotionEvent.ACTION_CANCEL -> { selected.clear(); invalidate() }
        }
        return true
    }
}

object PatternUi {
    private val worker = Executors.newSingleThreadExecutor()

    fun show(activity: Activity, setup: Boolean = false, success: () -> Unit,
             usePin: (() -> Unit)? = null, cancel: () -> Unit = {}): AlertDialog {
        val store = PatternStore(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(v:Int)=(v*density).toInt()
        var first: List<Int>? = null
        val root = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(8),dp(24),dp(8)) }
        val prompt = TextView(activity).apply {
            text=if(setup) "Draw a pattern using at least 4 dots" else "Draw your D2 pattern"
            textSize=15f; setTextColor(Appearance.secondary(activity))
        }
        val pattern = PatternView(activity)
        val error = TextView(activity).apply { textSize=14f; setTextColor(Color.rgb(220,70,70)) }
        root.addView(prompt); root.addView(pattern, LinearLayout.LayoutParams(-1,dp(310))); root.addView(error)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if(setup) "Create D2 pattern" else "Unlock D2")
            .setMessage("This pattern belongs only to D2 and does not change your Samsung screen lock.")
            .setView(root)
            .setNegativeButton(if(!setup && usePin!=null) "Use PIN instead" else "Cancel") { _,_ -> if(!setup && usePin!=null) usePin() else cancel() }
            .create()
        pattern.onComplete = complete@{ nodes ->
            if(nodes.size < 4) { error.text="Connect at least 4 dots."; return@complete }
            if(setup && first==null) { first=nodes; prompt.text="Draw the same pattern again"; error.text=""; return@complete }
            if(setup && first != nodes) { first=null; prompt.text="Draw a new pattern"; error.text="Patterns did not match. Try again."; return@complete }
            pattern.isEnabled=false; error.setTextColor(Appearance.secondary(activity)); error.text=if(setup) "Saving pattern…" else "Checking pattern…"
            worker.execute {
                var message="Pattern incorrect."
                val ok=try {
                    if(setup) { store.create(nodes); true } else store.verify(nodes)
                } catch (_:Exception) { message="D2 could not read or save the pattern. Access remains locked."; false }
                if(!ok && !setup) {
                    val seconds=runCatching { (store.remainingMillis()+999)/1000 }.getOrDefault(0)
                    if(seconds>0) message="Too many attempts. Wait $seconds seconds."
                }
                activity.runOnUiThread {
                    if(!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    if(ok) { dialog.dismiss(); success() } else { error.setTextColor(Color.rgb(220,70,70)); error.text=message; pattern.isEnabled=true }
                }
            }
        }
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius=dp(32).toFloat(); setColor(Appearance.surface(activity)); setStroke(dp(1),Appearance.secondary(activity))
            })
        }
        dialog.show()
        return dialog
    }
}
