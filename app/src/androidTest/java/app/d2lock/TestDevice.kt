package app.d2lock

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Prepare the disposable emulator, not the app's lock-screen implementation. */
object TestDevice {
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
    fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        shell("input keyevent KEYCODE_MENU")
        recoverSystemUiDialog()
    }
    private fun recoverSystemUiDialog() {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val root = ui.rootInActiveWindow ?: return
        if (root.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty()) return
        var wait = root.findAccessibilityNodeInfosByText("Wait").firstOrNull()
        while (wait != null && !wait.isClickable) wait = wait.parent
        check(wait?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
        android.os.SystemClock.sleep(3000)
    }
    fun focusDiagnostic(): String = shell("dumpsys window").lineSequence()
        .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("mShowingLockscreen") || it.contains("mAwake") }
        .joinToString("\n")
    fun focusTask(taskId: Int) { recoverSystemUiDialog(); shell("am task focus $taskId") }
}

