package org.example

import kotlin.time.Duration

sealed interface RetryStatus<out T> {
    data class Attempt(val attemptNumber: Int) :
        RetryStatus<Nothing>

    data class RetryScheduled(
        val attemptNumber: Int,
        val cause: Throwable,
        val delay: Duration,
        val isTimeout: Boolean,
    ) : RetryStatus<Nothing>

    data class Success<T>(val value: T) : RetryStatus<T>
    data class Exhausted(val cause: Throwable) : RetryStatus<Nothing>
}