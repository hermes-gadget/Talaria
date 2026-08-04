/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.core.util

import kotlinx.coroutines.CancellationException

/**
 * Cancellation-transparent suspend Result wrapper.
 *
 * `runCatching` swallows [CancellationException], which turns an orderly
 * cancel into a phantom failure (spurious error banners, stale retries).
 * Use this in suspend paths so cancellation always propagates:
 *
 * ```kotlin
 * val result = suspendResult { api.getSessions() }
 * ```
 *
 * Migrate `runCatching` call sites in suspend contexts to this helper;
 * keep `runCatching` only for genuinely non-cancellable (CPU-only) blocks.
 */
suspend fun <T> suspendResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
