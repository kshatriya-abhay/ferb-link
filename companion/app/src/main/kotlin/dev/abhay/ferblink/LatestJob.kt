package dev.abhay.ferblink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs jobs in [scope], the latest only: starting one cancels the one before, so a slow job that
 * started first (a check of the old address) can't finish last and overwrite a newer result.
 */
internal class LatestJob(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        job = scope.launch(block = block)
    }
}
