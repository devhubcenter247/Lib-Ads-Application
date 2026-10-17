import org.gradle.kotlin.dsl.`maven-publish`
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    // Kotlin/public classes live under com.lib.ads.application.*. Android
    // requires a distinct resource namespace from the consuming app.
    namespace = "com.lib.ads.gma.gma"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {

            isMinifyEnabled = false
            isShrinkResources = false
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

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-ads")
    exclude(group = "com.google.android.gms", module = "play-services-ads-lite")
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.ui)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    api("androidx.multidex:multidex:2.0.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")

    // Google Mobile Ads Next-Gen SDK
    api(libs.ads.mobile.sdk)
    api("com.google.android.gms:play-services-ads-identifier:18.3.0")
    api("com.google.android.gms:play-services-appset:16.1.0")
    api("com.google.android.gms:play-services-basement:18.11.0")
    api(libs.ump)
    api("com.google.android.play:review-ktx:2.0.2")

    // Firebase
    api(libs.firebase.analytics)

    // Billing
    api(libs.billing.ktx)

    // Analytics SDKs
    api(libs.adjust.android)
    api(libs.appsflyer.sdk)
    api(libs.appsflyer.adrevenue)
    // Install attribution for Adjust and AppsFlyer; declared explicitly instead of relying on facebook-core.
    api(libs.installreferrer)
    api(libs.facebook.sdk)

    // Mediation Adapters
    api(libs.mediation.facebook)
    api(libs.audience.network.sdk)
    api(libs.mediation.mintegral)
    api(libs.mediation.pangle)
    // Unity Ads mediation: the adapter does not pull the SDK in. Keep both on the same release family.
    api(libs.unity.ads)
    api(libs.mediation.unity)

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
    implementation("com.google.guava:guava:33.7.1-android")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.lib"
                artifactId = "gmasdk"
                version = "1.2.4"
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
