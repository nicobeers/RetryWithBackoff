# RetryBackoffUtil

A small Kotlin Coroutines utility for retrying suspending operations with exponential
backoff. `retryWithBackoff` wraps a `suspend` block and returns a `Flow<RetryStatus<T>>`
that emits each step of the retry lifecycle, attempt started, retry scheduled, success,
or exhaustion. Callers can observe (and react to) retries as they happen instead of
only seeing the final result.

## Usage

```kotlin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.example.RetryStatus
import org.example.retryWithBackoff
import kotlin.time.Duration.Companion.seconds

suspend fun fetchData(): String {
    // some suspending operation that might fail
    return "OK"
}

fun main() = runBlocking {
    retryWithBackoff(
        times = 3,
        initialDelay = 1.seconds,
        factor = 2.0,
    ) {
        fetchData()
    }.collect { status ->
        when (status) {
            is RetryStatus.Attempt -> println("Attempt #${status.attemptNumber}")
            is RetryStatus.RetryScheduled -> println("Retrying in ${status.delay} after: ${status.cause}")
            is RetryStatus.Success -> println("Succeeded with: ${status.value}")
            is RetryStatus.Exhausted -> println("Gave up after all attempts: ${status.cause}")
        }
    }
}
```

This project was built as a coding exercise for the **Coroutines Mastery** course by
[kt.academy](https://kt.academy).
