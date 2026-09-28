package com.c014.ai.art.style

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
private const val PHONE_PORTRAIT    = "spec:width=411dp,height=891dp,dpi=420"
private const val PHONE_LANDSCAPE   = "spec:width=891dp,height=411dp,dpi=420"
private const val PHONE_SMALL       = "spec:width=360dp,height=740dp,dpi=320"
private const val TABLET_PORTRAIT   = "spec:width=800dp,height=1280dp,dpi=240"
private const val TABLET_LANDSCAPE  = "spec:width=1280dp,height=800dp,dpi=240"
private const val FOLDABLE_OPEN     = "spec:width=673dp,height=841dp,dpi=420"

private const val DARK  = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
private const val LIGHT = Configuration.UI_MODE_NIGHT_NO  or Configuration.UI_MODE_TYPE_NORMAL
@Preview(name = "Phone · Dark", device = PHONE_PORTRAIT, uiMode = DARK, showBackground = true)
annotation class PhonePreview
@Preview(name = "Phone · Light", device = PHONE_PORTRAIT, uiMode = LIGHT, showBackground = true, showSystemUi = true)
annotation class PhoneLightPreview
@Preview(name = "Phone · Landscape", device = PHONE_LANDSCAPE, uiMode = DARK, showBackground = true)
annotation class PhoneLandscapePreview
@Preview(name = "Tablet · Landscape", device = TABLET_LANDSCAPE, uiMode = DARK, showBackground = true, widthDp = 1280, heightDp = 800)
annotation class TabletPreview
@Preview(name = "Tablet · Portrait", device = TABLET_PORTRAIT, uiMode = DARK, showBackground = true)
annotation class TabletPortraitPreview
@Preview(name = "Dark",  device = PHONE_PORTRAIT, uiMode = DARK,  showBackground = true)
@Preview(name = "Light", device = PHONE_PORTRAIT, uiMode = LIGHT, showBackground = true)
annotation class DarkLightPreview
@Preview(name = "Phone",  device = PHONE_PORTRAIT,   uiMode = DARK, showBackground = true)
@Preview(name = "Tablet", device = TABLET_LANDSCAPE, uiMode = DARK, showBackground = true)
annotation class ResponsivePreview
@Preview(name = "Phone · Dark",    device = PHONE_PORTRAIT,   uiMode = DARK,  showBackground = true)
@Preview(name = "Phone · Light",   device = PHONE_PORTRAIT,   uiMode = LIGHT, showBackground = true)
@Preview(name = "Tablet · Dark",   device = TABLET_LANDSCAPE, uiMode = DARK,  showBackground = true)
@Preview(name = "Tablet · Light",  device = TABLET_LANDSCAPE, uiMode = LIGHT, showBackground = true)
annotation class FullPreview
@Preview(name = "Phone Small", device = PHONE_SMALL, uiMode = DARK, showBackground = true)
annotation class SmallPhonePreview
@Preview(name = "Foldable · Open", device = FOLDABLE_OPEN, uiMode = DARK, showBackground = true)
annotation class FoldablePreview
