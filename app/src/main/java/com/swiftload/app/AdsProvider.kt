package com.swiftload.app

import android.view.View
import android.view.ViewGroup

/**
 * Empty ad slots. No ad SDK is included: every slot stays hidden.
 * Later, put an ad SDK's banner/native view into [container] here and make it visible.
 *
 * Slots: bottom of the Download tab, every 6th row of the Play tab list, the Share pop-up.
 */
object AdsProvider {
    const val SLOT_DOWNLOAD_TAB = "download_tab_bottom"
    const val SLOT_LIBRARY_LIST = "library_list"
    const val SLOT_SHARE_POPUP = "share_popup"

    /** Every Nth row of the Play tab list is an ad slot. */
    const val LIBRARY_EVERY = 6

    /** Set to true once a real ad SDK is wired in below. */
    private const val ENABLED = false

    fun bind(container: ViewGroup, slot: String) {
        container.removeAllViews()
        if (!ENABLED) {
            container.visibility = View.GONE
            return
        }
        // TODO: load an ad for [slot] into container, then:
        // container.visibility = View.VISIBLE
    }
}
