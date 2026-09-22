package app.d2lock

import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.lockscreen.LockScreenActivity
import app.d2lock.notifications.CallNotificationStore
import app.d2lock.notifications.LockNotification
import app.d2lock.notifications.NotificationStore
import app.d2lock.root.KioskCallApps
import app.d2lock.root.RootKiosk
import app.d2lock.security.PinStore
import app.d2lock.security.PatternUi
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Documentation fixtures only: real rendered UI, simulated notification content. */
@RunWith(AndroidJUnit4::class)
class ScreenshotCaptureTest {
 private val inst get() = InstrumentationRegistry.getInstrumentation()
 private val ctx get() = inst.targetContext
 private val ui get() = inst.uiAutomation
 private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText() }
 private fun <T> onMain(block: () -> T): T {
  var value: Result<T>? = null
  inst.runOnMainSync { value = runCatching(block) }
  return value!!.getOrThrow()
 }
 private fun views(root: View): Sequence<View> = sequence {
  yield(root)
  if(root is ViewGroup) for(i in 0 until root.childCount) yieldAll(views(root.getChildAt(i)))
 }
 private fun waitFor(label: String, condition: () -> Boolean) {
  val deadline=SystemClock.elapsedRealtime()+40000
  while(SystemClock.elapsedRealtime()<deadline) { if(condition()) return; SystemClock.sleep(200) }
  fail("Timed out: $label (${RootKiosk.diagnostic})")
 }
 private fun recoverBootDialog() {
  val root=ui.rootInActiveWindow ?: return
  if(root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()) return
  var button=root.findAccessibilityNodeInfosByText("Wait").firstOrNull()
  while(button!=null && !button.isClickable) button=button.parent
  check(button?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true)
  SystemClock.sleep(10000)
 }
 private fun connect(): RootKiosk.Channel {
  val packages=KioskCallApps.resolve(ctx).joinToString(" ")
  val pipes=ui.executeShellCommandRw("su 0 env CLASSPATH=${ctx.applicationInfo.sourceDir} /system/bin/app_process /system/bin app.d2lock.root.KioskBridge 0 $packages")
  val output=ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
  return RootKiosk.Channel(ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader(), output.bufferedWriter()) { runCatching { output.close() } }
 }
 private fun <T: android.app.Activity> capture(scenario: ActivityScenario<T>, name: String) {
  scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
  inst.waitForIdleSync(); SystemClock.sleep(700)
  val bitmap=checkNotNull(ui.takeScreenshot())
  val pipes=ui.executeShellCommandRw("dd of=/data/local/tmp/d2-screenshots/$name.png")
  ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }; bitmap.recycle()
 }
 @Test fun captureDocumentation() {
  assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots")=="true")
  shell("mkdir -p /data/local/tmp/d2-screenshots")
  TestDevice.wake(); recoverBootDialog()
  assertFalse("Fresh emulator required",File(ctx.noBackupFilesDir,"d2-pin.json").exists())
  PinStore(ctx).create("246810".toCharArray())
  // App-only mode is sufficient to document this appearance-only update.
  Prefs.setKiosk(ctx,false); Prefs.setShowMedia(ctx,true)
  Prefs.setNotificationPrivacy(ctx,4); Prefs.setLiveNotifications(ctx,true)
  shell("cmd notification allow_listener app.d2lock/app.d2lock.notifications.LockNotificationListener")
  SystemClock.sleep(1500)

  val scenario=ActivityScenario.launch<LockScreenActivity>(Intent(ctx,LockScreenActivity::class.java))
  try {
   var activity: LockScreenActivity?=null
   var taskId = -1
   scenario.onActivity { activity=it; taskId=it.taskId }
   TestDevice.wake(); TestDevice.focusTask(taskId)
   recoverBootDialog()
   waitFor("D2 focus") { onMain { activity!!.hasWindowFocus() } }
   // Appearance captures use app-only mode; the separate 19-test suite verifies kiosk.
   fun pending(id: Int) = PendingIntent.getBroadcast(ctx,id,Intent("app.d2lock.DEMO_CALL_$id").setPackage(ctx.packageName),PendingIntent.FLAG_IMMUTABLE)
   val notification=Notification.Builder(ctx,"screenshot-demo")
    .setSmallIcon(android.R.drawable.sym_call_incoming).setCategory(Notification.CATEGORY_CALL).setOngoing(true)
    .setStyle(Notification.CallStyle.forIncomingCall(Person.Builder().setName("Demo caller").build(),pending(1),pending(2)))
    .setContentIntent(pending(3)).build()
   val sbn=StatusBarNotification(ctx.packageName,ctx.packageName,42,null,Process.myUid(),Process.myPid(),0,notification,Process.myUserHandle(),System.currentTimeMillis())
   onMain {
    NotificationStore.items.clear()
    // Explicit test fixture; production still trusts only actual Phone/system call packages.
    CallNotificationStore.update(sbn,setOf(ctx.packageName))
    NotificationStore.onChanged?.invoke()
    assertEquals(2,CallNotificationStore.items.single().controls.size)
    assertTrue(views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="Open phone call" })
   }
   capture(scenario,"12-d2-044-call-controls-demo")
   onMain {
    CallNotificationStore.items.clear()
    val message=LockNotification("demo-message","Messages (demo)","Alex · Sample message","The new update is ready. See you soon!",System.currentTimeMillis(),Notification.VISIBILITY_PRIVATE)
    NotificationStore.items.add(message)
    NotificationStore.items.add(LockNotification("demo-calendar","Calendar (demo)","Tomorrow · Sample event","Coffee at 10:00",System.currentTimeMillis(),Notification.VISIBILITY_PUBLIC))
    NotificationStore.items.add(LockNotification("demo-mail","Mail (demo)","Sam · Sample email","Your weekend plans",System.currentTimeMillis(),Notification.VISIBILITY_PRIVATE))
    NotificationStore.onChanged?.invoke(); NotificationStore.onPosted?.invoke(message)
    assertTrue(views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.visibility==View.VISIBLE && it.text.toString().contains("Messages (demo)\n") })
   }
   capture(scenario,"13-d2-044-centered-notifications")
   for ((mode,name) in listOf(1 to "14-d2-044-light",3 to "15-d2-044-amoled-floating-bar")) {
    Appearance.set(ctx,"mode",mode); Appearance.set(ctx,"accent",if(mode==1) 1 else 2)
    Appearance.set(ctx,"bar_width",75); Appearance.set(ctx,"bar_opacity",65); Appearance.set(ctx,"bar_gap",40)
    scenario.recreate()
    scenario.onActivity { activity=it }
    waitFor("themed screen focus") { onMain { activity!!.hasWindowFocus() } }
    capture(scenario,name)
   }
  } finally {
   val done=CountDownLatch(1)
   scenario.onActivity { RootKiosk.unlock(it) { done.countDown() } }
   assertTrue(done.await(15,TimeUnit.SECONDS))
   scenario.close(); RootKiosk.testConnect=null
   NotificationStore.items.clear(); CallNotificationStore.items.clear()
  }
  // Capture the actual D2 pattern dialog from the built app.
  ActivityScenario.launch<MainActivity>(Intent(ctx,MainActivity::class.java)).use { patternHost ->
   SystemClock.sleep(1000)
   patternHost.onActivity { a ->
    a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    val d=PatternUi.show(a, setup=true, success={})
    d.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
   }
   SystemClock.sleep(900)
   capture(patternHost,"18-d2-glass-pattern-create")
   // Draw a representative in-progress gesture so the selected orb and glass trail are visible.
   shell("input swipe 245 720 475 945 900")
   SystemClock.sleep(250)
   capture(patternHost,"19-d2-glass-pattern-trail")
  }

  // Opening settings also verifies theme initialization before its content is attached.
  ActivityScenario.launch(MainActivity::class.java).use { settings ->
   SystemClock.sleep(1200)
   settings.onActivity { a ->
    val dialog=a.javaClass.getDeclaredField("pinDialog").apply { isAccessible=true }.get(a) as android.app.AlertDialog
    views(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText("246810")
    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
   }
   waitFor("theme settings ready") { onMain { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { a -> views(a.window.decorView).filterIsInstance<TextView>().any { it.text.toString()=="THEME & COLORS" } } } }
   for ((title,name) in listOf("THEME & COLORS" to "16-d2-044-theme-settings","FLOATING LOCK-SCREEN BAR" to "17-d2-044-bar-settings")) {
    settings.onActivity { a ->
     val all=views(a.window.decorView).toList()
     val target=all.filterIsInstance<TextView>().first { it.text.toString()==title }
     all.filterIsInstance<android.widget.ScrollView>().first().scrollTo(0,target.top)
    }
    capture(settings,name)
   }
  }
 }
}
