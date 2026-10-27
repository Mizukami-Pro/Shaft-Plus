package ceui.pixiv.widgets

/** Compatibility no-op. shaft+ intentionally never tracks engagement for rating prompts. */
@Deprecated("Rating prompts are disabled in shaft+.")
object RateAppManager {
    /** Retained for legacy call sites; intentionally does nothing. */
    @JvmStatic
    fun onUserEngaged() = Unit

    /** Retained for legacy call sites; a rating prompt is never eligible. */
    @JvmStatic
    fun shouldShowRateDialog(): Boolean = false

    /** Retained for legacy call sites; intentionally does nothing. */
    @JvmStatic
    fun onAutoShown() = Unit
}
