package org.example

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.*

/**
 * Runs [block] with retries and exponential backoff, emitting a [RetryStatus] for each
 * lifecycle event (attempt started, retry scheduled, success, or exhaustion).
 *
 * @param times maximum number of attempts (must be >= 1).
 * @param initialDelay delay before the first retry (must be positive).
 * @param factor multiplier applied to the delay after each failed attempt (must be >= 1.0).
 * @param maxDelay upper bound the backoff delay is clamped to (must be >= [initialDelay]).
 * @param block the suspending operation to retry.
 * @return a [Flow] of [RetryStatus] events describing the retry lifecycle.
 * @throws CancellationException if the collecting coroutine is cancelled.
 */
fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelay: Duration = 1.seconds,
    factor: Double = 2.0,
    maxDelay: Duration = 1.minutes,
    block: suspend () -> T,
): Flow<RetryStatus<T>> = flow {
    validateRetryParams(times, initialDelay, factor, maxDelay)
    attempt(times, 1, initialDelay, factor, maxDelay, block)
}

private suspend fun <T> FlowCollector<RetryStatus<T>>.attempt(
    times: Int,
    attemptNumber: Int,
    currentDelay: Duration,
    factor: Double,
    maxDelay: Duration,
    block: suspend () -> T,
) {
    currentCoroutineContext().ensureActive()
    emit(RetryStatus.Attempt(attemptNumber))

    when (val outcome = runAttempt(block)) {
        is AttemptOutcome.Success -> emit(RetryStatus.Success(outcome.value))
        is AttemptOutcome.Failure -> if (attemptNumber == times) {
            emit(RetryStatus.Exhausted(outcome.cause))
        } else {
            emit(RetryStatus.RetryScheduled(attemptNumber, outcome.cause, currentDelay, outcome.cause is TimeoutCancellationException))
            delay(currentDelay)
            attempt(
                times = times,
                attemptNumber = attemptNumber + 1,
                currentDelay = (currentDelay * factor).coerceAtMost(maxDelay),
                factor = factor,
                maxDelay = maxDelay,
                block = block,
            )
        }
    }
}

private fun validateRetryParams(times: Int, initialDelay: Duration, factor: Double, maxDelay: Duration) {
    require(times >= 1) { "times must be >= 1, was $times" }
    require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
    require(initialDelay > Duration.ZERO) { "initialDelay must be positive, was $initialDelay" }
    require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
}

private sealed interface AttemptOutcome<out T> {
    data class Success<T>(val value: T) : AttemptOutcome<T>
    data class Failure(val cause: Throwable) : AttemptOutcome<Nothing>
}

private suspend fun <T> runAttempt(
    block: suspend () -> T,
): AttemptOutcome<T> = try {
    AttemptOutcome.Success(block())
} catch (e: TimeoutCancellationException) {
    AttemptOutcome.Failure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AttemptOutcome.Failure(e)
}
