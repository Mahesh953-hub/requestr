plugins {
  alias(libs.plugins.android.library)
}

// Requestr Observer SDK — zero-dependency-on-app instrumentation library that any Android
// app (not just requestr itself) can embed to report its own outbound HTTP calls to a
// running requestr instance's embedded Local API server for correlation/observation. See
// RequestrObserver.kt for usage. Built as its own Gradle module (S1) so it can be published
// standalone (e.g. via `./gradlew :sdk:assembleRelease`) without depending on `:app`.
android {
  namespace = "com.requestr.sdk"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  // org.json.* (used for the /api/observe payload) ships in the Android platform SDK, no
  // separate dependency needed.
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)

  // Task 7: automatic HTTP stack instrumentation. All three third-party stacks are
  // `compileOnly` so the SDK never forces them onto an embedding app that doesn't already
  // use them -- only the stack(s) the host app itself depends on will actually be present
  // on its runtime classpath, so the corresponding interceptor activates and the others
  // simply reference classes that never get loaded.
  compileOnly("io.ktor:ktor-client-core:2.3.0")
  compileOnly("com.google.android.gms:play-services-cronet:18.1.0")

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  testImplementation("io.ktor:ktor-client-core:2.3.0")
  testImplementation("io.ktor:ktor-client-cio:2.3.0")
}
