package com.lib.ads.gma.ads.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import com.lib.ads.gma.ads.util.hideSystemBar
import com.lib.ads.gma.ads.util.setFullScreen
import com.lib.ads.gma.gma.R
import androidx.core.graphics.drawable.toDrawable

class ResumeLoadingDialog(context: Context) : Dialog(context, R.style.AppTheme) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_resume_loading)
        window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setDimAmount(0f)
            setFullScreen()
            hideSystemBar()
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setWindowAnimations(R.style.LoadingDialogAnimation)
        }
    }
}
