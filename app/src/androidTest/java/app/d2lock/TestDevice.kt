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
    }
    fun focusDiagnostic(): String = shell("dumpsys window").lineSequence()
        .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("mShowingLockscreen") || it.contains("mAwake") }
        .joinToString("\n")
    fun focusTask(taskId: Int) { shell("am task focus $taskId") }
}
