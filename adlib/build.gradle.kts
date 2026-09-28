import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.lib.adlib"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    publishing {
        singleVariant("release")
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Lifecycle
    annotationProcessor("androidx.lifecycle:lifecycle-compiler:2.10.0")
    implementation("androidx.lifecycle:lifecycle-extensions:2.2.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.10.0")

    // AndroidX UI
    implementation("androidx.cardview:cardview:1.0.0")
    api("androidx.multidex:multidex:2.0.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.activity:activity-ktx:1.12.2")

    // Google Play Services & Ads
    api("com.google.android.gms:play-services-ads:25.5.0")
    api("com.google.android.gms:play-services-ads-identifier:18.3.0")
    api("com.google.android.gms:play-services-appset:16.1.0")
    api("com.google.android.gms:play-services-basement:18.10.0")
    api("com.google.android.ump:user-messaging-platform:4.0.0")
    api("com.google.android.play:review-ktx:2.0.2")

    // Firebase
    api("com.google.firebase:firebase-analytics:23.2.0")

    // Billing
    api("com.android.billingclient:billing-ktx:9.1.0")

    // Analytics SDKs
    api("com.adjust.sdk:adjust-android:5.7.0")
    api("com.appsflyer:adrevenue:6.9.1")
    api("com.facebook.android:facebook-android-sdk:18.2.3")
    compileOnly("com.facebook.infer.annotation:infer-annotation:0.18.0")

    // Mediation Adapters
    api("com.google.ads.mediation:facebook:6.21.0.3")
    api("com.facebook.android:audience-network-sdk:6.21.0")
    api("com.google.ads.mediation:mintegral:17.1.61.0")
    api("com.google.ads.mediation:pangle:8.0.0.4.0")
    // Unity Ads mediation. Keep the SDK and adapter on the same release family.
    api("com.unity3d.ads:unity-ads:4.19.0")
    api("com.google.ads.mediation:unity:4.19.0.0")

    // UI Libraries
    implementation("com.airbnb.android:lottie:6.7.1")
    implementation("com.facebook.shimmer:shimmer:0.5.0")
    implementation("com.intuit.sdp:sdp-android:1.1.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.retrofit2:adapter-rxjava2:3.0.0")
    implementation("com.squareup.okhttp3:logging-interceptor:5.3.2")

    // Utilities
    implementation("com.google.guava:guava:33.6.0-android")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Arrow-kt (Functional Programming)
    api("io.arrow-kt:arrow-core:2.2.2.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.lib"
                artifactId = "adlib"
                version = "1.7.3"
            }
        }
        repositories {
            val gprUser = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            val gprKey = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/dbv0610/AdsApplication")
                credentials {
                    username = gprUser
                    password = gprKey
                }
            }
        }
    }
}
