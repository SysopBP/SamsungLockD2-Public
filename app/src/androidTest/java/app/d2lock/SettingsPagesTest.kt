package app.d2lock

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.d2lock.security.PinStore
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsPagesTest {
    @Test fun unavailableFingerprintFallsBackToPinAndDetailNavigationSurvivesRecreation() {
        TestDevice.wake()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val credential=File(context.noBackupFilesDir,"d2-pin.json")
        val previous=if(credential.exists()) credential.readBytes() else null
        val prefs=context.getSharedPreferences("lock_preferences",0)
        val saved=prefs.all.toMap()
        try {
            credential.delete()
            PinStore(context).create("246810".toCharArray())
            prefs.edit().clear().putString("unlock_method","fingerprint").putBoolean("setup_wizard_seen",true)
                .putBoolean("setup_wizard_complete",true).putInt("intro_seen_version",29).commit()
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use { scenario ->
                fun login() {
                    onView(withId(R.id.d2_pin_input)).inRoot(isDialog()).perform(typeText("246810"),closeSoftKeyboard())
                    onView(withText("Unlock")).inRoot(isDialog()).perform(click())
                    val deadline=SystemClock.uptimeMillis()+5000
                    while(SystemClock.uptimeMillis()<deadline) {
                        var ready=false
                        scenario.onActivity { ready=it.window.decorView.findViewWithTag<android.view.View>("settings_scroll")!=null }
                        if(ready) return
                        SystemClock.sleep(50)
                    }
                    throw AssertionError("PIN fallback did not open settings")
                }
                login()
                onView(withContentDescription("Security")).perform(click())
                onView(withText(startsWith("Security center  ›"))).perform(click())
                onView(allOf(withText("Change PIN"),isDisplayed())).check(matches(isDisplayed()))
                scenario.recreate()
                login()
                onView(allOf(withText("Change PIN"),isDisplayed())).check(matches(isDisplayed()))
                onView(withText("‹ Back")).perform(click())
                onView(withText(startsWith("Security center  ›"))).check(matches(isDisplayed()))
                onView(withContentDescription(containsString("CORE search"))).perform(click())
                onView(withHint("Search D2 settings")).perform(typeText("Change PIN"),closeSoftKeyboard())
                onView(withText(containsString("Change PIN\nSECURITY CENTER"))).perform(click())
                onView(allOf(withText("Change PIN"),isDisplayed())).check(matches(isDisplayed()))
                // Searching the same visible detail again must not add a duplicate Back entry.
                onView(withContentDescription(containsString("CORE search"))).perform(click())
                onView(withHint("Search D2 settings")).perform(typeText("Change PIN"),closeSoftKeyboard())
                onView(withText(containsString("Change PIN\nSECURITY CENTER"))).perform(click())
                onView(withText("‹ Back")).perform(click())
                onView(withText(startsWith("Security center  ›"))).check(matches(isDisplayed()))
                assertTrue(PinStore(context).verify("246810".toCharArray()))
            }
        } finally {
            if(previous==null) credential.delete() else credential.writeBytes(previous)
            val edit=prefs.edit().clear()
            saved.forEach { (key,value) -> when(value) {
                is Boolean -> edit.putBoolean(key,value)
                is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value)
                is Float -> edit.putFloat(key,value)
                is String -> edit.putString(key,value)
            } }
            edit.commit()
        }
    }
}
