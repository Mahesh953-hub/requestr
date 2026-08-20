# requestr ProGuard / R8 rules
#
# -dontoptimize: disables R8's aggressive whole-program optimisations
# (inlining, class merging, vertical/horizontal class merging, etc.)
# while keeping code shrinking and obfuscation. These optimisations
# break Compose reflection, BouncyCastle JCE service-loader patterns,
# Room annotation processing, and Moshi code-gen.
#
# The APK with -dontoptimize is ~4-6 MB larger but stable.
-dontoptimize

# ── Kotlin ──────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
-keep class kotlin.coroutines.Continuation
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# ── Jetpack Compose ────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** { *; }

# ── Room ────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class androidx.room.** { *; }
-keep class androidx.sqlite.** { *; }
-dontwarn androidx.room.paging.**

# ── DataStore ───────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.** { *; }

# ── Kotlin Serialization ────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── BouncyCastle (CA generation, PKCS#12, JCE provider) ────────────
# BouncyCastle uses JCA service-loader patterns and reflection to
# register itself as a security provider. R8 -optimize strips the 
# META-INF/services entries and inlines provider constructors.
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keepdirectories org/bouncycastle
-keepattributes Signature,Exceptions,*Annotation*

# ── java.security / javax.crypto (KeyStore, KeyPairGenerator) ──────
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }
-keepclassmembers class java.security.KeyStore {
    public static getInstance(...);
}
-keepclassmembers class java.security.KeyPairGenerator {
    public static getInstance(...);
}

# ── Android VPN / security ──────────────────────────────────────────
-keep class android.net.VpnService { *; }
-keep class android.net.VpnService$Builder { *; }
-keep class android.security.KeyChain { *; }
-keep class android.security.KeyChainException { *; }

# ── Shizuku (system-level IPC) ──────────────────────────────────────
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.** { *; }

# ── libsu (Root access) ─────────────────────────────────────────────
-keep class com.topjohnwu.superuser.** { *; }
-keepclassmembers class com.topjohnwu.superuser.** { *; }

# ── OkHttp ──────────────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-keepclassmembers class okhttp3.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Moshi (JSON code-gen) ──────────────────────────────────────────
-keep class com.squareup.moshi.** { *; }
-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier interface *
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}

# ── Gson (EnvironmentRepository, ApiTestr) ──────────────────────────
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Retrofit ────────────────────────────────────────────────────────
-keep class retrofit2.** { *; }
-keepclassmembers class retrofit2.** { *; }

# ── Firebase ────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Navigation Compose ──────────────────────────────────────────────
-keep class androidx.navigation.** { *; }

# ── Coroutines ──────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ── App data models (Room entities + JSON types) ────────────────────
-keep class com.example.data.models.** { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.data.interception.** { *; }
-keep class com.example.data.prefs.** { *; }
-keep class com.example.data.env.** { *; }

# ── Interception engine ─────────────────────────────────────────────
-keep class com.example.interception.** { *; }
-keep class com.example.interception.http.** { *; }
-keep class com.example.interception.tcp.** { *; }
-keep class com.example.interception.tls.** { *; }
-keep class com.example.interception.dns.** { *; }
-keep class com.example.interception.quic.** { *; }

# ── Flow engine ─────────────────────────────────────────────────────
-keep class com.example.flow.** { *; }

# ── UI screens (Compose reflection targets) ─────────────────────────
-keep class com.example.ui.screens.** { *; }
-keep class com.example.ui.components.** { *; }
-keep class com.example.ui.theme.** { *; }

# ── Repositories ────────────────────────────────────────────────────
-keep class com.example.data.repository.** { *; }

# ── Preserve line numbers for crash stack traces ───────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes InnerClasses,EnclosingMethod
-keepattributes Exceptions,Signature,Deprecated

# ── Safe dontwarn (harmless platform-level missing classes) ────────
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn com.google.android.gms.internal.recaptchabase.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn sun.misc.Unsafe
