/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.meta.spatial.plugin)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "com.orgista.openpanel.quest"
  //noinspection GradleDependency
  compileSdk = 34

  defaultConfig {
    applicationId = "com.orgista.openpanel.quest"
    minSdk = 34
    // HorizonOS is Android 14 (API level 34)
    //noinspection OldTargetApi,ExpiredTargetSdkVersion
    targetSdk = 34
    versionCode = 65
    versionName = "0.3.53"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Update the ndkVersion to the right version for your app
    // ndkVersion = "27.0.12077973"
  }

  packaging { resources.excludes.add("META-INF/LICENSE") }

  lint {
    abortOnError = false
    checkReleaseBuilds = false
  }

  // Upload signing comes from the shared deployment env (Apps/signing/
  // credentials.env) — same pattern as the other Orgista apps. Without these
  // variables the release build stays unsigned, which is how the first
  // openpanelvr AAB shipped unsigned.
  signingConfigs {
    create("release") {
      val keystorePath = providers.environmentVariable("OPENPANEL_QUEST_KEYSTORE_FILE").orNull
      if (!keystorePath.isNullOrBlank()) {
        storeFile = file(keystorePath)
        storePassword = providers.environmentVariable("OPENPANEL_QUEST_KEYSTORE_PASS").orNull
        keyAlias = providers.environmentVariable("OPENPANEL_QUEST_KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("OPENPANEL_QUEST_KEY_PASS").orNull
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  sourceSets.getByName("main").assets.exclude("**/scenes/**")
}

// Dev tooling (hotreload, castinputforward, datamodelinspector, ovrmetrics)
// runs services on-device and was part of the launcher's idle CPU/heat
// problem, so it never ships in a default build. Iteration builds opt in with
// `-PdevTools` (scripts/gradle-local.sh -PdevTools assembleDebug).
val devToolsEnabled = providers.gradleProperty("devTools").isPresent

//noinspection UseTomlInstead
dependencies {
  implementation(libs.androidx.core.ktx)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)

  // compose
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.ui.tooling.preview)
  debugImplementation(libs.androidx.ui.tooling)

  // Meta Spatial SDK libs
  implementation(libs.meta.spatial.sdk.base)
  implementation(libs.meta.spatial.sdk.compose)
  implementation(libs.meta.spatial.sdk.toolkit)
  implementation(libs.meta.spatial.sdk.vr)
  implementation(libs.meta.spatial.sdk.uiset)
  // Interaction SDK: native panel grab + in-place resize (0.13.2).
  implementation(libs.meta.spatial.sdk.isdk)
  // Mixed Reality Utility Kit: scene understanding for passthrough mode.
  implementation(libs.meta.spatial.sdk.mruk)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  // Gapless looping for the 360 video dome (plain MediaPlayer flashes an
  // invalid surface buffer at loop wrap -> compositor purple).
  implementation("androidx.media3:media3-exoplayer:1.4.1")

  if (devToolsEnabled) {
    implementation(libs.meta.spatial.sdk.hotreload)
    implementation(libs.meta.spatial.sdk.castinputforward)
    implementation(libs.meta.spatial.sdk.datamodelinspector)
    implementation(libs.meta.spatial.sdk.ovrmetrics)
  }
}

val projectDir = layout.projectDirectory

spatial {
  allowUsageDataCollection.set(true)
  scenes {
    // if you have installed Meta Spatial Editor somewhere else, update the file path.

    cliPath.set("/Applications/Meta Spatial Editor.app/Contents/MacOS/CLI")

    hotReload {
      appPackage.set("com.orgista.openpanel.quest")
      appMainActivity.set(".OpenPanelActivity")
      assetsDir.set(File("src/main/assets"))
    }
  }
}
