package com.pocketscope.device

import com.pocketscope.camera.CaptureResult
import com.pocketscope.camera.LensInfo

sealed class CaptureOutcome {
    data class Success(val result: CaptureResult) : CaptureOutcome()
    object Busy : CaptureOutcome()
    data class Error(val cause: Exception) : CaptureOutcome()
}

interface CaptureDevice {
    val lensInfo: LensInfo
    val isBusy: Boolean
    suspend fun capture(exposureNanos: Long, isoValue: Int): CaptureOutcome
}
