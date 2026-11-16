package dev.abhay.ferblink

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.Test
import java.util.Collections

/** The screen's checks: a slow check of the old address never overwrites the new one's result. */
class LatestJobTest {
    @Test
    fun `starting a job cancels the one before, even one blocked on IO`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val results = Collections.synchronizedList(mutableListOf<String>())
        val latest = LatestJob(scope)
        latest.launch { withContext(Dispatchers.IO) { Thread.sleep(400) }; results += "old address" }
        Thread.sleep(50)
        latest.launch { withContext(Dispatchers.IO) { Thread.sleep(50) }; results += "new address" }
        Thread.sleep(800)
        scope.cancel()
        assertThat(results).containsExactly("new address")
    }
}
