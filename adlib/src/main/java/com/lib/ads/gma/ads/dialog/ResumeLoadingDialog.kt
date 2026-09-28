package com.lib.ads.gma.ads.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import com.lib.adlib.R
import com.lib.ads.gma.ads.util.hideSystemBar
import com.lib.ads.gma.ads.util.setFullScreen

/**
 * Dialog shown while loading resume ads.
 */
class ResumeLoadingDialog(context: Context) : Dialog(context, R.style.AppTheme) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_resume_loading)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0f)
            setFullScreen()
            hideSystemBar()
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.LoadingDialogAnimation)
        }
    }
}
