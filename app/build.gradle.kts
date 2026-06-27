plugins {
      alias(libs.plugins.android.application)
      alias(libs.plugins.kotlin.android)
  }

  android {
      namespace = "com.vcam"
      compileSdk = 35

      defaultConfig {
          applicationId = "com.vcam"
          minSdk = 26
          targetSdk = 35
          versionCode = 1
          versionName = "1.0"

          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          
          ndk {
              abiFilters += listOf("armeabi-v7a", "arm64-v8a")
          }

          externalNativeBuild {
              cmake {
                  cppFlags += "-std=c++17"
                  arguments += "-DANDROID_STL=c++_shared"
              }
          }
      }

      buildTypes {
          release {
              isMinifyEnabled = false
              proguardFiles(
                  getDefaultProguardFile("proguard-android-optimize.txt"),
                  "proguard-rules.pro"
              )
          }
          debug {
              isDebuggable = true
          }
      }

      compileOptions {
          sourceCompatibility = JavaVersion.VERSION_11
          targetCompatibility = JavaVersion.VERSION_11
      }

      kotlinOptions {
          jvmTarget = "11"
      }

      buildFeatures {
          viewBinding = true
      }

      externalNativeBuild {
          cmake {
              path = file("src/main/jni/CMakeLists.txt")
              version = "3.22.1"
          }
      }

      packaging {
          // Preserve the prebuilt injection binaries exactly as-is.
          // vcplax.so is a PIE executable (not a shared lib); must not be stripped.
          // libvc.so and libshadowhook.so use symbol-name hooks that must be intact.
          jniLibs {
              keepDebugSymbols += setOf(
                  "**/vcplax.so",
                  "**/libvc.so",
                  "**/libshadowhook.so"
              )
          }
      }
  }

  dependencies {
      implementation(libs.androidx.core.ktx)
      implementation(libs.androidx.appcompat)
      implementation(libs.material)
      implementation(libs.androidx.constraintlayout)
      implementation(libs.androidx.lifecycle.viewmodel)
      implementation(libs.androidx.lifecycle.runtime)
      implementation(libs.androidx.activity.ktx)
      implementation(libs.libsu.core)
      implementation(libs.libsu.service)
      implementation(libs.glide)
      implementation(libs.kotlinx.coroutines.android)
  }
