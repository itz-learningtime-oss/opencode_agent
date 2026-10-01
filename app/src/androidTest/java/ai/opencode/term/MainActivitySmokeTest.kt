package ai.opencode.term

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val rule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityLaunches() {
        rule.scenario.onActivity { activity ->
            assertNotNull(activity.findViewById<android.view.View>(R.id.terminal_view))
        }
    }
}
