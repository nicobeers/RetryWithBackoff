import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.example.RetryStatus
import org.example.retryWithBackoff
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RetryWithBackoffTest {

    @Test
    fun `should succeed on first attempt without any delay`() = runTest {
        val statuses = retryWithBackoff(times = 3, initialDelay = 1.seconds) { "OK" }.toList()

        assertEquals(listOf(RetryStatus.Attempt(1), RetryStatus.Success("OK")), statuses)
        assertEquals(0, currentTime)
    }

    @Test
    fun `should apply exponential backoff between attempts`() = runTest {
        var attempts = 0
        val statuses = retryWithBackoff(times = 4, initialDelay = 1.seconds, factor = 2.0) {
            attempts++
            if (attempts < 4) throw RuntimeException("fail $attempts")
            "OK"
        }.toList()

        assertEquals(4, attempts)
        assertEquals(7_000, currentTime) // 1s + 2s + 4s
        assertIs<RetryStatus.Success<String>>(statuses.last())

        val delays = statuses.filterIsInstance<RetryStatus.RetryScheduled>().map { it.delay }
        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds), delays)
    }

    @Test
    fun `should emit Exhausted after exceeding the retry limit`() = runTest {
        val exception = RuntimeException("always fails")

        val statuses = retryWithBackoff(times = 3, initialDelay = 1.seconds) { throw exception }.toList()

        val last = statuses.last()
        assertIs<RetryStatus.Exhausted>(last)
        assertEquals(exception, last.cause)
        assertEquals(3, statuses.count { it is RetryStatus.Attempt })
        assertEquals(1_000 + 2_000, currentTime) // no delay is scheduled after the final attempt
    }

    @Test
    fun `should cap the delay at maxDelay`() = runTest {
        val statuses = retryWithBackoff(
            times = 5,
            initialDelay = 1.seconds,
            factor = 10.0,
            maxDelay = 3.seconds,
        ) { throw RuntimeException("fail") }.toList()

        val delays = statuses.filterIsInstance<RetryStatus.RetryScheduled>().map { it.delay }
        assertEquals(listOf(1.seconds, 3.seconds, 3.seconds, 3.seconds), delays)
    }

    @Test
    fun `should stop retrying immediately when the collecting coroutine is cancelled`() = runTest {
        var attempts = 0

        val job = launch {
            retryWithBackoff(times = 10, initialDelay = 1.seconds) {
                attempts++
                throw RuntimeException("fail")
            }.collect()
        }

        runCurrent() // let the first attempt run; the coroutine then suspends inside delay(1s)
        assertEquals(1, attempts)

        job.cancel()
        job.join()

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, attempts) // no further attempts happened after cancellation
    }

    @Test
    fun `should treat TimeoutCancellationException as a distinct retryable failure`() = runTest {
        var attempts = 0

        val statuses = retryWithBackoff(times = 3, initialDelay = 1.seconds) {
            attempts++
            if (attempts < 3) {
                withTimeout(100) {
                    delay(1_000) // never completes in time -> TimeoutCancellationException
                }
            }
            "OK"
        }.toList()

        val scheduled = statuses.filterIsInstance<RetryStatus.RetryScheduled>()
        assertEquals(2, scheduled.size)
        assertTrue(scheduled.all { it.isTimeout })
        assertTrue(scheduled.all { it.cause is TimeoutCancellationException })
        assertIs<RetryStatus.Success<String>>(statuses.last())
    }

    @Test
    fun `should propagate a plain CancellationException without retrying`() = runTest {
        var attempts = 0

        val job = launch {
            retryWithBackoff(times = 5, initialDelay = 1.seconds) {
                attempts++
                throw CancellationException("stop")
            }.collect()
        }
        job.join()

        assertEquals(1, attempts)
    }
}