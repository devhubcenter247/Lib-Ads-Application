package com.lib.ads.gma.ads.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.airbnb.lottie.LottieAnimationView
import com.lib.ads.gma.ads.util.hideSystemBar
import com.lib.ads.gma.ads.util.setFullScreen
import com.lib.ads.gma.gma.R

/**
 * @param showLoadingCard When false, skips the spinner/text content and shows only the opaque
 * window background. Use this for a dialog covering an Activity that's about to pause/stop (e.g.
 * a transition-gap cover shown right before navigating away): a stopped Activity's window stops
 * being drawn, so a Lottie animation on it freezes on a static frame instead of looping -- there's
 * no point starting one that will never actually animate.
 */
class PrepareLoadingAdsDialog(
    context: Context,
    private val showLoadingCard: Boolean = true,
) : Dialog(
    context,
    R.style.AppTheme
) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setFullScreen()
            hideSystemBar()
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(R.layout.dialog_prepair_loading_ads)
        if (!showLoadingCard) {
            findViewById<View>(R.id.loading_dialog_lottie)?.visibility = View.GONE
            findViewById<View>(R.id.loading_dialog_tv)?.visibility = View.GONE
        }
        findViewById<LottieAnimationView>(R.id.loading_dialog_lottie)?.apply {
            // autoPlay may advance the animation before the dialog has rendered.
            cancelAnimation()
            progress = 0f
            post { playAnimation() }
        }
    }
}
