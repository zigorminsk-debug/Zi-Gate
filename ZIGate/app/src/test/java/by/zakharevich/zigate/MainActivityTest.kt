package by.zakharevich.zigate

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class MainActivityTest {
    @Test
    fun onCreateDoesNotThrow() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .start()
            .get()
        println("ACTIVITY_CREATED=${activity::class.java.simpleName}")
    }
}
