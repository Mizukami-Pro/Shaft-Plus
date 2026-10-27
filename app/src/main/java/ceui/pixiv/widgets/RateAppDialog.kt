package ceui.pixiv.widgets

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

/**
 * Compatibility tombstone for rating-dialog states created by older Shaft versions.
 *
 * Rating prompts are disabled in shaft+. Keep this class under its historic name so AndroidX can
 * safely restore an old fragment instance after an app upgrade; the restored fragment never creates
 * a window, never opens a store URL, and dismisses itself when resumed.
 */
@Deprecated("Rating prompts are disabled in shaft+.")
class RateAppDialog : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowsDialog(false)
    }

    override fun onResume() {
        super.onResume()
        dismissAllowingStateLoss()
    }

    companion object {
        /** Retained for legacy callers; the rating dialog can never be shown. */
        @JvmStatic
        fun isShowing(fragmentManager: FragmentManager): Boolean = false

        /** Intentionally does nothing. */
        @JvmStatic
        fun showIfNeeded(fragmentManager: FragmentManager) = Unit

        /** Store review is disabled along with the dialog. */
        @JvmStatic
        fun isPlayStoreAvailable(context: Context): Boolean = false
    }
}
