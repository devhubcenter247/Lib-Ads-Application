plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "com.lib.ads.gma"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lib.ads.application"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += "default"
    productFlavors {
        create("appDev") {
            //use id test when dev
            manifestPlaceholders["ad_app_id"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ad_interstitial_splash", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_inter_splash_piority", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_banner", "\"/21775744923/example/adaptive-banner\"")
            buildConfigField("String", "ad_reward", "\"/21775744923/example/rewarded\"")
            buildConfigField("String", "ad_banner_collapse", "\"/21775744923/example/adaptive-banner\"")
            buildConfigField("String", "ad_reward_inter", "\"/21775744923/example/rewarded-interstitial\"")
            buildConfigField("String", "ad_appopen_resume", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ad_native", "\"/21775744923/example/native\"")
            buildConfigField("String", "ad_native_high", "\"/21775744923/example/native-video\"")
            buildConfigField("String", "ads_open_app", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ads_open_app_high", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ad_inter_priority", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_inter_normal", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_fb_native_banner", "\"/21775744923/example/native\"")
            buildConfigField("Boolean", "env_dev", "true")
        }
        create("appProd") {
            //add your id ad here
            manifestPlaceholders["ad_app_id"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ad_interstitial_splash", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_inter_splash_piority", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_banner", "\"/21775744923/example/adaptive-banner\"")
            buildConfigField("String", "ad_reward", "\"/21775744923/example/rewarded\"")
            buildConfigField("String", "ad_banner_collapse", "\"/21775744923/example/adaptive-banner\"")
            buildConfigField("String", "ad_reward_inter", "\"/21775744923/example/rewarded-interstitial\"")
            buildConfigField("String", "ad_appopen_resume", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ad_native", "\"/21775744923/example/native\"")
            buildConfigField("String", "ad_native_high", "\"/21775744923/example/native-video\"")
            buildConfigField("String", "ads_open_app", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ads_open_app_high", "\"/21775744923/example/app-open\"")
            buildConfigField("String", "ad_inter_priority", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_inter_normal", "\"/21775744923/example/interstitial\"")
            buildConfigField("String", "ad_fb_native_banner", "\"/21775744923/example/native\"")
            buildConfigField("Boolean", "env_dev", "false")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
   // The app is intentionally compiled against gma-lib alone. adlib remains
   // included in the repository for parallel consumers and regression tests,
   // but cannot be on this app's classpath because both modules expose legacy
   // package names with incompatible GMA types.
   implementation(project(":gma-lib"))
    implementation(libs.ads.mobile.sdk)
    implementation(libs.androidx.activity.ktx)
    // implementation("com.lib:adlib:1.0.0-alpha")

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.material3)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // AndroidX UI (app-specific)
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("dev.chrisbanes.haze:haze:1.7.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.android.sdp.ssp)
}
