package com.example.berryharvest.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Extension functions for working with repositories.
 */

/**
 * Transform a repository Result into a Flow with loading state.
 */
fun <T> Result<T>.asFlow(): Flow<Result<T>> = kotlinx.coroutines.flow.flow {
    emit(this@asFlow)
}

/**
 * Create a flow that starts with Loading state.
 */
fun <T> Flow<Result<T>>.withLoading(): Flow<Result<T>> {
    return this
        .onStart { emit(Result.Loading) }
        .catch { emit(Result.Error(Exception(it))) }
}

/**
 * Map the data of a Result Flow.
 */
fun <T, R> Flow<Result<T>>.mapData(transform: (T) -> R): Flow<Result<R>> {
    return this.map { result ->
        when (result) {
            is Result.Success -> Result.Success(transform(result.data))
            is Result.Error -> result
            is Result.Loading -> Result.Loading
        }
    }
}

/**
 * Helper to execute code when a Result is successful.
 */
inline fun <T> Result<T>.onSuccessUI(crossinline block: (T) -> Unit): Result<T> {
    if (this is Result.Success) {
        block(data)
    }
    return this
}

/**
 * Helper to execute code when a Result fails.
 */
inline fun <T> Result<T>.onErrorUI(crossinline block: (String) -> Unit): Result<T> {
    if (this is Result.Error) {
        block(message)
    }
    return this
}

