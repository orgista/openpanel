/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.jetbrains.kotlin.android) apply false
}

val openPanelLocalBuildRoot = System.getenv("OPENPANEL_GRADLE_BUILD_ROOT")
if (!openPanelLocalBuildRoot.isNullOrBlank()) {
  allprojects {
    layout.buildDirectory.set(file("$openPanelLocalBuildRoot/$name"))
  }
}
