package com.lib.ads.gma.app.gma.onboarding

import com.lib.ads.gma.R

sealed class OnboardingPage {
    data class Slide(
        val index: Int,
        val image: Int,
        val title: Int,
        val content: Int
    ) : OnboardingPage()

}

fun setupOnboardingPage(): List<OnboardingPage> = buildList {
    add(
        OnboardingPage.Slide(
            index = 0,
            image = R.drawable.img_onboarding_1,
            title = R.string.title_onboarding_1,
            content = R.string.content_onboarding_1,
        )
    )
    add(
        OnboardingPage.Slide(
            index = 1,
            image = R.drawable.img_onboarding_1,
            title = R.string.title_onboarding_2,
            content = R.string.content_onboarding_2,
        )
    )

    add(
        OnboardingPage.Slide(
            index = 2,
            image = R.drawable.img_onboarding_3,
            title = R.string.title_onboarding_3,
            content = R.string.content_onboarding_3,
        )
    )

    add(
        OnboardingPage.Slide(
            index = 3,
            image = R.drawable.img_onboarding_4,
            title = R.string.title_onboarding_4,
            content = R.string.content_onboarding_4,
        )
    )
}
