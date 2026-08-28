plugins {
  alias(libs.plugins.android.library)
}

// Phase 6 (docs/plans/MASTER-FEATURE-ADDITION-PLAN.md §3 "No-op module swap for the sdk
// module"): a same-package, empty-behavior counterpart to :sdk. A host app picks exactly one
// of :sdk or :sdk-noop as a build-variant dependency (e.g.
// `debugImplementation(project(":sdk"))` / `releaseImplementation(project(":sdk-noop"))`) to
// strip every bit of interception/capture code out of a production APK without touching a
// single call site -- every class/object/function this module exposes has the identical
// signature as :sdk's equivalent, just with an empty body. See README.md for exactly which
// classes are (and are intentionally NOT) mirrored, and why.
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
  // Kept in lockstep with :sdk's own dependency set so host-app call sites
  // (RequestrSdk.okHttpBuilder(...)/.cronetEngineBuilder(...)/.installKtorPlugin(...)/
  // .grpcChannelBuilder(...)/.newWebSocket(...)) resolve to identical parameter/return types
  // regardless of which of the two modules is actually on the compile classpath.
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)

  // Same compileOnly shape as :sdk -- these stay compileOnly here for the same reason: an app
  // that never touches Ktor/Cronet/gRPC never pays for (or crashes on) a missing class, and the
  // no-op module's whole point is to cost even less than the real one, never more.
  compileOnly("io.ktor:ktor-client-core:2.3.0")
  compileOnly("com.google.android.gms:play-services-cronet:18.1.0")
  compileOnly(libs.grpc.api)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  testImplementation("io.ktor:ktor-client-core:2.3.0")
  testImplementation("com.google.android.gms:play-services-cronet:18.1.0")
  testImplementation(libs.grpc.api)
}
