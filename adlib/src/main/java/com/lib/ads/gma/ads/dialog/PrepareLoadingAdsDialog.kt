package com.lib.ads.gma.ads.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.lib.adlib.R
import com.airbnb.lottie.LottieAnimationView
import com.lib.ads.gma.ads.util.hideSystemBar
import com.lib.ads.gma.ads.util.setFullScreen

/** Dialog shown while preparing to load ads. The caller must provide a window-capable context. */
class PrepareLoadingAdsDialog(
    context: Context,
    private val showLoadingCard: Boolean = true,
) :
    Dialog(
        context,
        R.style.AppTheme
    ) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFullScreen()
            hideSystemBar()
            setDimAmount(0f)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.LoadingDialogAnimation)
        }
    }

    fun hideLoadingAdsText() {
        findViewById<View>(R.id.loading_dialog_tv)?.visibility = View.INVISIBLE
    }
}
