package app.d2lock

import android.app.Activity
import android.app.AlertDialog
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Switch
import android.widget.TextView
import android.widget.EditText
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.d2lock.bridge.IslandBridge
import app.d2lock.security.PinStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Disposable emulator documentation only. Never included in the distributed app. */
@RunWith(AndroidJUnit4::class)
class ComboScreenshotTest {
 private val inst get() = InstrumentationRegistry.getInstrumentation()
 private val ctx get() = inst.targetContext
 private val ui get() = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).apply {
  serviceInfo=serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
 }
 private fun <T> onMain(block: () -> T): T {
  var value: Result<T>?=null
  inst.runOnMainSync { value=runCatching(block) }
  return value!!.getOrThrow()
 }
 private fun activity(): Activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
 private fun views(root: View): Sequence<View> = sequence {
  yield(root)
  if(root is ViewGroup) for(i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
 }
 private fun enterPin() = onMain {
  val a=activity()
  val dialog=a.javaClass.getDeclaredField("pinDialog").apply { isAccessible=true }.get(a) as AlertDialog
  views(dialog.window!!.decorView).filterIsInstance<EditText>().single().setText("246810")
  dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
 }
 private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
 private fun waitFor(label: String, condition: () -> Boolean) {
  val deadline=SystemClock.elapsedRealtime()+40000
  while(SystemClock.elapsedRealtime()<deadline) { if(condition()) return; SystemClock.sleep(250) }
  fail("Timed out: $label")
 }
 private fun overlayVisible() = ui.windows.any { it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
 private fun recoverBootSystemUi() {
  val root=ui.rootInActiveWindow ?: return
  if(root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()) return
  var waitButton=root.findAccessibilityNodeInfosByText("Wait").firstOrNull()
  while(waitButton!=null && !waitButton.isClickable) waitButton=waitButton.parent
  check(waitButton?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
  SystemClock.sleep(15000)
 }
 private fun capture(name: String) {
  inst.runOnMainSync {
   ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { activity ->
    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    runCatching { (activity.javaClass.getDeclaredField("pinDialog").apply { isAccessible=true }.get(activity) as? AlertDialog)?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
   }
  }
  inst.waitForIdleSync();SystemClock.sleep(1200)
  val bitmap=checkNotNull(ui.takeScreenshot())
  val pipes=ui.executeShellCommandRw("dd of=/data/local/tmp/combo-screenshots/$name.png")
  ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() };bitmap.recycle()
 }
 @Test fun capturePairedApps() {
  shell("mkdir -p /data/local/tmp/combo-screenshots")
  ui.serviceInfo=ui.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
  recoverBootSystemUi()
  assertFalse("Fresh emulator required",File(ctx.noBackupFilesDir,"d2-pin.json").exists())
  PinStore(ctx).create("246810".toCharArray());IslandBridge.setEnabled(ctx,true);IslandBridge.setLocked(ctx,false)
  shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");shell("input keyevent KEYCODE_MENU")
  ActivityScenario.launch(MainActivity::class.java).use { scenario ->
   var taskId = -1
   scenario.onActivity { taskId = it.taskId }
   shell("am task focus $taskId")
   shell("wm dismiss-keyguard");shell("input keyevent KEYCODE_MENU")
   SystemClock.sleep(1500)
   recoverBootSystemUi()
   println(shell("dumpsys window").lineSequence().filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }.joinToString("\n"))
   capture("00-diagnostic-initial-pin")
   enterPin()
   waitFor("authenticated settings") { onMain {
    val label=views(activity().window.decorView).filterIsInstance<Switch>().find { it.text.toString()=="Connect Galaxy Island (paired build)" }
    if(label==null) false else { assertEquals(Color.WHITE,label.currentTextColor);true }
   } }
   capture("01-d2-paired-settings")
   scenario.onActivity { a ->
    val all=views(a.window.decorView).toList()
    val target=all.filterIsInstance<TextView>().first { it.text.toString()=="When D2 is locked" }
    all.filterIsInstance<ScrollView>().first().scrollTo(0,target.top-(80*a.resources.displayMetrics.density).toInt())
   }
   capture("02-d2-customization")
  }
  shell("am start -W -n app.cutout.ringpreview/com.ekoehler.expressivecutout.MainActivity")
  SystemClock.sleep(5000)
  recoverBootSystemUi()
  // Rebind after the app's first launch has cleared its stopped-package state.
  shell("settings put secure enabled_accessibility_services app.cutout.ringpreview/com.ekoehler.expressivecutout.service.CutoutAccessibilityService")
  shell("settings put secure accessibility_enabled 1")
  capture("03-diagnostic-galaxy-startup")
  waitFor("island available"){overlayVisible()}
  // Refresh permission state after the emulator's shell grant and service binding.
  shell("input keyevent KEYCODE_HOME")
  shell("am start -W -n app.cutout.ringpreview/com.ekoehler.expressivecutout.MainActivity")
  SystemClock.sleep(2500)
  capture("03-galaxy-island-settings")
  shell("input keyevent KEYCODE_HOME");SystemClock.sleep(1500)
  shell("cmd notification post -S bigtext -t Combo-preview combo Emulator-notification")
  SystemClock.sleep(1800);capture("04-island-notification")
  // The emulator preset places the collapsed island at 6dp offset + half its 34dp height.
  val x=ctx.resources.displayMetrics.widthPixels/2;val y=(23*ctx.resources.displayMetrics.density).toInt()
  repeat(2) {
   val now=SystemClock.uptimeMillis()
   val down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x.toFloat(),y.toFloat(),0)
   down.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;ui.injectInputEvent(down,true);down.recycle()
   val up=android.view.MotionEvent.obtain(now,now+30,android.view.MotionEvent.ACTION_UP,x.toFloat(),y.toFloat(),0)
   up.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;ui.injectInputEvent(up,true);up.recycle();SystemClock.sleep(70)
  }
  waitFor("double-tap activated D2"){IslandBridge.locked(ctx)}
  waitFor("island hidden while locked"){!overlayVisible()}
  waitFor("D2 lock screen rendered") { onMain {
   runCatching { activity() is app.d2lock.lockscreen.LockScreenActivity && views(activity().window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="PIN" } }.getOrDefault(false)
  } }
  capture("05-d2-lock-island-hidden")
  onMain { views(activity().window.decorView).filterIsInstance<TextView>().first { it.text.toString()=="PIN" }.performClick() }
  capture("06-d2-pin-prompt")
  enterPin()
  waitFor("PIN cleared bridge lock"){!IslandBridge.locked(ctx)}
  shell("input keyevent KEYCODE_HOME")
  waitFor("island restored"){overlayVisible()};capture("07-island-restored-after-pin")
 }
}
