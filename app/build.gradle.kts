import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Collections

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// ---- Release signing overrides -----------------------------------------------------------
// Release signing is overridable via Gradle properties OR environment variables so a
// build host or CI can supply its own keystore without editing source. When no override
// is present, the literal compatibility fallbacks below are used so existing local
// builds keep working unchanged. The resolved identity is asserted by the
// `verifyReleaseSigning` task (alias == "upload" and certificate SHA-256 == the locked
// release fingerprint from task 2) before any release APK is assembled. Passwords are
// never logged or printed.
val releaseStoreFile: org.gradle.api.provider.Provider<String> =
  providers.gradleProperty("RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("RELEASE_STORE_FILE"))
    .orElse("${rootDir}/requestr-release.jks")
val releaseStorePassword: org.gradle.api.provider.Provider<String> =
  providers.gradleProperty("RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_STORE_PASSWORD"))
    .orElse("requestr")
val releaseKeyAlias: org.gradle.api.provider.Provider<String> =
  providers.gradleProperty("RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("RELEASE_KEY_ALIAS"))
    .orElse("upload")
val releaseKeyPassword: org.gradle.api.provider.Provider<String> =
  providers.gradleProperty("RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("RELEASE_KEY_PASSWORD"))
    .orElse("requestr")
// Debug signing uses a regenerated, non-preserved local keystore. Override the path via
// DEBUG_STORE_FILE (default: <rootDir>/debug.keystore, which is gitignored). The debug
// signing identity is intentionally NOT preserved across builds/machines.
val debugStoreFile: org.gradle.api.provider.Provider<String> =
  providers.gradleProperty("DEBUG_STORE_FILE")
    .orElse(providers.environmentVariable("DEBUG_STORE_FILE"))
    .orElse("${rootDir}/debug.keystore")

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.kailashhh.requestr"
    minSdk = 24
    targetSdk = 36
    versionCode = 28
    versionName = "6.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      storeFile = file(releaseStoreFile.get())
      storePassword = releaseStorePassword.get()
      keyAlias = releaseKeyAlias.get()
      keyPassword = releaseKeyPassword.get()
    }
    create("debugConfig") {
      storeFile = file(debugStoreFile.get())
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debugConfig") }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      // P2: QuicParser.parseInitial() calls android.util.Log.d() on its success path (not just
      // in a catch block like the pre-existing code did), which throws "not mocked" under
      // plain JVM unit tests without Robolectric. Returning defaults for unmocked Android SDK
      // calls is the standard fix instead of adding a Robolectric runner to every quic test.
      isReturnDefaultValues = true
    }
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  sourceSets {
    // Task 10: MigrationTestHelper (Migration8To9Test) needs the exported schema JSON files as
    // test assets so it can build a real v8 database on disk to migrate from, instead of relying
    // on fallbackToDestructiveMigration ever being exercised again. Robolectric unit tests load
    // assets from the merged "debug" variant assets (see generateDebugUnitTestConfig's
    // android_merged_assets), not a separate test-only assets dir, so this is wired on "main"
    // rather than "test".
    getByName("main") {
      assets.srcDirs("$projectDir/schemas")
    }
  }
}

// Asserts the resolved release signing identity before any release APK is assembled: the
// keystore must exist, the resolved alias must be "upload", and the certificate SHA-256
// must equal the locked release fingerprint captured in task 2. The store/key passwords
// are read from the resolved providers and are NEVER logged or printed (only the public
// certificate fingerprint is emitted for evidence).
tasks.register("verifyReleaseSigning") {
  group = "verification"
  description = "Verify the resolved release keystore alias and certificate SHA-256 fingerprint."
  // Resolve at configuration time so the doLast action only captures serializable
  // locals (Strings / java.io.File) and never the script-level Provider objects.
  val storePath: String = releaseStoreFile.get()
  val resolvedAlias: String = releaseKeyAlias.get()
  val storePassword: String = releaseStorePassword.get()
  val storeFile: java.io.File = file(storePath)
  doLast {
    if (!storeFile.exists()) {
      throw GradleException(
        "Release keystore not found at '$storePath'. Set RELEASE_STORE_FILE " +
          "(Gradle property or env var) to the keystore path."
      )
    }
    val keystore = KeyStore.getInstance("PKCS12")
    storeFile.inputStream().use { input ->
      keystore.load(input, storePassword.toCharArray())
    }
    if (!keystore.containsAlias(resolvedAlias)) {
      throw GradleException(
        "Release keystore '$storePath' has no alias '$resolvedAlias'. Aliases present: " +
          "${Collections.list(keystore.aliases())}."
      )
    }
    check(resolvedAlias == "upload") {
      "Resolved release key alias must be 'upload' but was '$resolvedAlias'."
    }
    val certificate = keystore.getCertificate(resolvedAlias)
    val sha256 = MessageDigest.getInstance("SHA-256")
      .digest(certificate.encoded)
      .joinToString(":") { "%02X".format(it) }
    val lockedFingerprint =
      "CB:08:8B:DE:A4:85:3C:D9:06:A2:CF:EF:4A:61:B4:FB:59:96:C6:75:78:A6:BE:DE:97:18:17:32:C5:DA:6E:D8"
    check(sha256 == lockedFingerprint) {
      "Release certificate SHA-256 mismatch. Got $sha256 but the locked release " +
        "identity (task 2) is $lockedFingerprint."
    }
    logger.lifecycle(
      "verifyReleaseSigning OK: alias='$resolvedAlias', certificate SHA-256=$sha256 " +
        "(matches the locked release identity)."
    )
  }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
  dependsOn("verifyReleaseSigning")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Room schema v9 (task 10): export the schema JSON so future migrations are verifiable against
// a real prior-version snapshot instead of relying on fallbackToDestructiveMigration.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  // v6.0 transplant (manifest §5.8) — Coil for TrackArtworkView album-art loading.
  implementation(libs.coil.compose)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.libsu.core)
  implementation(libs.libsu.io)
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
  implementation(libs.bouncycastle.bcpkix)
  implementation(libs.bouncycastle.bcprov)
  implementation(libs.cronet.embedded)
  implementation(libs.androidx.webkit)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  // Master plan §1.9 — MockWebServer for OkHttp-backed unit tests of ApiTestrRepository
  // (mirrors the coordinate already used by sdk/build.gradle.kts).
  testImplementation("com.squareup.okhttp3:mockwebserver:4.10.0")
  // Task 10: MigrationTestHelper for Migration8To9Test, now that exportSchema = true gives it a
  // real prior-version (8.json) schema snapshot to migrate from.
  testImplementation(libs.androidx.room.testing)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}
