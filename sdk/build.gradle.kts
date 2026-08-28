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

  testOptions {
    unitTests.all {
      // HttpUrlConnectionWrapperTest reflects into java.net.URL's package-private
      // getURLStreamHandler(String) + private static `handlers` field (see
      // HttpUrlConnectionWrapper.systemHandlerFor/evictHandlerCache kdoc) to capture and
      // uncache the platform's real http(s) handler before installing our own factory. On
      // JDK 9+ that requires the test JVM to actually open java.net to unnamed modules --
      // without this, `setAccessible(true)` throws InaccessibleObjectException in the test
      // JVM (module boundaries don't exist on real Android/ART, only on the desktop JVM
      // Robolectric/JUnit run under).
      it.jvmArgs(
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        // SslSecretExtractorTest reflects into a real Conscrypt handshake's SSLSession object
        // graph (see the test's kdoc) -- JDK 9+ blocks setAccessible(true) on javax.net.ssl /
        // java.lang internals from an unnamed module without this.
        "--add-opens=java.base/javax.net.ssl=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
      )
    }
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
  // Phase 6: gRPC capture (GrpcInterceptor.kt). Same compileOnly shape as Cronet/Ktor above --
  // an app that never touches gRPC never pays for (or crashes on) a missing class. Note there
  // is deliberately NO protobuf dependency here, not even compileOnly: GrpcMessageRenderer
  // reaches `com.google.protobuf.util.JsonFormat` purely via reflection so this module never
  // forces a specific protobuf-java(-util) version onto a host app that may pin its own.
  compileOnly(libs.grpc.api)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.core)
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  testImplementation("io.ktor:ktor-client-core:2.3.0")
  testImplementation("io.ktor:ktor-client-cio:2.3.0")
  // CronetInterceptorTest needs the real ExperimentalCronetEngine.Builder/ICronetEngineBuilder
  // classes to construct a builder against a fake delegate (no native engine runs in a JVM
  // unit test -- see the test's kdoc). compileOnly above is not visible to the test source set.
  testImplementation("com.google.android.gms:play-services-cronet:18.1.0")
  // SslSecretExtractorTest runs a real TLS 1.2/1.3 handshake through Conscrypt's actual JVM
  // artifact (not Android's bundled copy, which can't run outside ART) to honestly document
  // SslSecretExtractor's real-world behavior against this build's real internal class shapes,
  // confirmed via decompilation -- see the test's kdoc.
  testImplementation("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
  // GrpcInterceptorTest needs the real io.grpc.* API types (ClientCall/Channel/Metadata/Status/
  // MethodDescriptor) to exercise RequestrGrpcInterceptor against a hand-rolled fake Channel --
  // same "real API types, fake delegate, no native/network layer" shape as CronetInterceptorTest.
  // compileOnly above is not visible to the test source set.
  testImplementation(libs.grpc.api)
  // Only used by GrpcInterceptorTest to prove the reflective protobuf-JSON path in
  // GrpcMessageRenderer actually activates when protobuf-java-util IS on the classpath (the
  // common case for a real gRPC host app) -- com.google.protobuf.StringValue is a
  // pre-generated well-known-type message from protobuf-java itself, so no protoc step is
  // needed just to exercise this.
  testImplementation(libs.protobuf.java)
  testImplementation(libs.protobuf.java.util)
}
