package app.d2lock

import android.app.ActivityManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.content.ContentValues
import android.provider.MediaStore
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
import app.d2lock.security.PinUi
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
 private fun previewWallpaper(): String {
  val bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(bitmap)
  val paint=Paint(Paint.ANTI_ALIAS_FLAG)
  paint.shader=LinearGradient(0f,0f,1080f,2400f,intArrayOf(0xff08090c.toInt(),0xff252a31.toInt(),0xff050506.toInt()),null,Shader.TileMode.CLAMP)
  canvas.drawRect(0f,0f,1080f,2400f,paint)
  paint.shader=null
  paint.style=Paint.Style.STROKE
  paint.strokeWidth=3f
  paint.color=0x42ffffff
  for(i in 0..8) canvas.drawCircle(840f-i*70f,420f+i*185f,250f+i*38f,paint)
  paint.style=Paint.Style.FILL
  paint.color=0x18ffffff
  canvas.drawCircle(180f,1780f,520f,paint)
  val values=ContentValues().apply {
   put(MediaStore.Images.Media.DISPLAY_NAME,"d2_beta1_monochrome_wallpaper.png")
   put(MediaStore.Images.Media.MIME_TYPE,"image/png")
   put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/D2Preview")
  }
  val uri=checkNotNull(ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
  ctx.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
  bitmap.recycle()
  return uri.toString()
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
  val wallpaper=previewWallpaper()
  Prefs.setWallpaper(ctx,wallpaper); Prefs.setAppWallpaper(ctx,wallpaper)
  Prefs.setWallpaperAmoled(ctx,true); Prefs.setWallpaperDim(ctx,42); Prefs.setAppWallpaperDim(ctx,34)
  Prefs.setWallpaperZoom(ctx,108); Prefs.setWallpaperOffsetY(ctx,-8)
  Appearance.set(ctx,"mode",3)
  Appearance.set(ctx,"accent",11); Appearance.set(ctx,"custom",0xffd8d8d8.toInt())
  Appearance.set(ctx,"notifications",2)
  Appearance.set(ctx,"cards",42); Appearance.set(ctx,"banners",52)
  Appearance.set(ctx,"bar_width",78); Appearance.set(ctx,"bar_opacity",52); Appearance.set(ctx,"bar_gap",34)
  Prefs.applyProfile(ctx,"amoled")
  Prefs.setWallpaper(ctx,wallpaper); Prefs.setAppWallpaper(ctx,wallpaper)
  Prefs.setWallpaperAmoled(ctx,true); Prefs.setWallpaperDim(ctx,42); Prefs.setAppWallpaperDim(ctx,34)
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
   capture(scenario,"01-beta1-monochrome-call-controls")
   onMain {
    CallNotificationStore.items.clear()
    val message=LockNotification("demo-message","Messages (demo)","Alex · Sample message","The new update is ready. See you soon!",System.currentTimeMillis(),Notification.VISIBILITY_PRIVATE)
    NotificationStore.items.add(message)
    NotificationStore.items.add(LockNotification("demo-calendar","Calendar (demo)","Tomorrow · Sample event","Coffee at 10:00",System.currentTimeMillis(),Notification.VISIBILITY_PUBLIC))
    NotificationStore.items.add(LockNotification("demo-mail","Mail (demo)","Sam · Sample email","Your weekend plans",System.currentTimeMillis(),Notification.VISIBILITY_PRIVATE))
    NotificationStore.onChanged?.invoke(); NotificationStore.onPosted?.invoke(message)
    assertTrue(views(activity!!.window.decorView).filterIsInstance<TextView>().any { it.visibility==View.VISIBLE && it.text.toString().contains("Messages (demo)\n") })
   }
   capture(scenario,"02-beta1-monochrome-notifications")
   onMain {
    val dialog=PinUi.show(activity!!,success={})
    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
   }
   capture(scenario,"03-beta1-glass-pin")
   onMain {
    val dialogs=views(activity!!.window.decorView).toList()
    // PinUi is a separate window; dismiss the visible dialog through instrumentation before pattern preview.
   }
   ui.pressBack(); SystemClock.sleep(400)
   onMain {
    val dialog=PatternUi.show(activity!!,setup=true,success={})
    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
   }
   capture(scenario,"04-beta1-glass-pattern")
   ui.pressBack(); SystemClock.sleep(400)
   Appearance.set(ctx,"mode",3); Appearance.set(ctx,"accent",11); Appearance.set(ctx,"custom",0xffd8d8d8.toInt()); Appearance.set(ctx,"notifications",2)
   scenario.recreate()
   scenario.onActivity { activity=it }
   waitFor("AMOLED wallpaper focus") { onMain { activity!!.hasWindowFocus() } }
   capture(scenario,"05-beta1-amoled-wallpaper-floating-bar")
  } finally {
   val done=CountDownLatch(1)
   scenario.onActivity { RootKiosk.unlock(it) { done.countDown() } }
   assertTrue(done.await(15,TimeUnit.SECONDS))
   scenario.close(); RootKiosk.testConnect=null
   NotificationStore.items.clear(); CallNotificationStore.items.clear()
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
   capture(settings,"06-beta1-settings-home-wallpaper")
   for ((title,name) in listOf(
    "THEME & COLORS" to "07-beta1-theme-monochrome",
    "APP ICON" to "08-beta1-launcher-icons",
    "APP THEME" to "09-beta1-wallpaper-studio",
    "FLOATING BAR" to "10-beta1-floating-bar-shortcuts",
    "D2 APP BACKGROUND" to "11-beta1-app-background",
    "LOCK-SCREEN PROFILES" to "12-beta1-lockscreen-profiles",
    "BACKUP & RESTORE" to "13-beta1-backup-restore"
   )) {
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
