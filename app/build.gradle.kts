plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

android {
  namespace = "ai.sunnypilot.navcompanion"
  compileSdk = 36

  flavorDimensions += "platform"

  defaultConfig {
    applicationId = "ai.sunnypilot.navcompanion"
    // Phone users get minSdk 26 so the App runs on older Android handsets. The carauto flavor
    // overrides this because androidx.car.app requires minSdk 29.
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  productFlavors {
    create("phone") {
      dimension = "platform"
      // Inherits minSdk 26 from defaultConfig.
    }
    create("carauto") {
      dimension = "platform"
      // AndroidX Car App library (androidx.car.app) requires API 29+.
      minSdk = 29
      versionNameSuffix = "-carauto"
      // Suffix the APK so both the phone build and the car build can co-exist on a phone.
      applicationIdSuffix = ".car"
    }
  }

  buildFeatures {
    compose = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2025.12.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")

  implementation("androidx.core:core-ktx:1.16.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

  implementation("org.maplibre.gl:android-sdk:11.13.5")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")

  // Android Auto / Android Automotive OS integration — only bundled in the carauto flavor.
  // androidx.car.app:app 1.7.0 requires minSdk 29 (carauto flavor overrides defaultConfig's 26).
  add("carautoImplementation", "androidx.car.app:app:1.7.0")
}
