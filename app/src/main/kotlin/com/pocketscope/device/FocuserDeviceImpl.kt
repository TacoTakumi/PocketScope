package com.pocketscope.device

import com.pocketscope.camera.LensInfo

class FocuserDeviceImpl(
    override val maxSteps: Int = DEFAULT_MAX_STEPS
) : FocuserDevice {

    companion object {
        const val DEFAULT_MAX_STEPS = 1000
    }

    override var currentPosition: Int = 0
        private set

    override var activeLensInfo: LensInfo? = null
        private set

    override fun moveAbsolute(position: Int): Int {
        currentPosition = position.coerceIn(0, maxSteps)
        return currentPosition
    }

    override fun moveRelative(steps: Int, outward: Boolean): Int {
        currentPosition = if (outward) {
            (currentPosition + steps)
        } else {
            (currentPosition - steps)
        }.coerceIn(0, maxSteps)
        return currentPosition
    }

    override fun switchActiveLens(newLensInfo: LensInfo?) {
        activeLensInfo = newLensInfo
        currentPosition = 0
    }

    override fun currentDiopters(): Float {
        val minFocus = activeLensInfo?.minFocusDistance ?: return 0f
        return (currentPosition.toFloat() / maxSteps) * minFocus
    }
}
