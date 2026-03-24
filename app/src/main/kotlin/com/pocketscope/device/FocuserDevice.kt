package com.pocketscope.device

import com.pocketscope.camera.LensInfo

interface FocuserDevice {
    val maxSteps: Int
    val currentPosition: Int
    val activeLensInfo: LensInfo?

    fun moveAbsolute(position: Int): Int
    fun moveRelative(steps: Int, outward: Boolean): Int
    fun switchActiveLens(newLensInfo: LensInfo?)
    fun currentDiopters(): Float
}
