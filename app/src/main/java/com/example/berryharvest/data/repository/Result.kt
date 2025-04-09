package com.example.berryharvest.data.repository

/**
 * A generic class that holds a value with its loading status.
 * @param T The type of data to be wrapped.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Exception, val message: String = exception.localizedMessage ?: "Unknown error") : Result<Nothing>()
    object Loading : Result<Nothing>()

    // Helper function to handle success case
    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    // Helper function to handle error case
    inline fun onError(action: (Error) -> Unit): Result<T> {
        if (this is Error) action(this)
        return this
    }

    // Helper function to handle loading case
    inline fun onLoading(action: () -> Unit): Result<T> {
        if (this is Loading) action()
        return this
    }

    // Map the result to another type
    fun <R> map(transform: (T) -> R): Result<R> {
        return when (this) {
            is Success -> Success(transform(data))
            is Error -> this
            is Loading -> Loading
        }
    }

    // Get the data or null if it's not Success
    fun getOrNull(): T? {
        return (this as? Success)?.data
    }

    // Get the data or throw exception if it's not Success
    fun getOrThrow(): T {
        return when (this) {
            is Success -> data
            is Error -> throw exception
            is Loading -> throw IllegalStateException("Result is still loading")
        }
    }
}