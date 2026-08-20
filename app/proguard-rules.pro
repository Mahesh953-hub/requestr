# requestr ProGuard / R8 rules (Fix #38 — enable minification)

# ── Kotlin ──────────────────────────────────────────────────────────
-keepclassmembers class **$CompanionObject { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers public class ** { public <init>(...); }
-dontwarn kotlin.**

# ── Jetpack Compose ────────────────────────────────────────────────
# Compose uses reflection to resolve composable functions.
-keepclasseswithmembers public class ** { @androidx.compose.runtime.Composable public *; }
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ── Room ────────────────────────────────────────────────────────────
-keep class androidx.room.RoomDatabase { *; }
-keep @interface androidx.room.Entity
-keep @interface androidx.room.Dao
-keep @interface androidx.room.Query
-keep class ** extends androidx.room.RoomDatabase
-keep class ** { @androidx.room.* <methods>; }
-dontwarn androidx.room.**

# ── DataStore ───────────────────────────────────────────────────────
-keepclassmembers class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }
-dontwarn androidx.datastore.**

# ── Kotlin Serialization / DataStore protobuf ──────────────────────
-keepclasseswithmembers class * {
    kotlinx.serialization.Serializable <fields>;
}

# ── BouncyCastle (CA generation, PKCS#12) ───────────────────────────
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── java.security (KeyStore, KeyPairGenerator) ──────────────────────
-keepclassmembers class java.security.** { *; }
-keepclassmembers class javax.crypto.** { *; }
-dontwarn javax.crypto.**
-dontwarn java.security.**

# ── Android / system classes used by reflection ─────────────────────
-keepclassmembers class android.security.KeyChain { *; }
-keepclassmembers class android.net.VpnService { *; }
-keepclassmembers class android.net.VpnService$Builder { *; }

# ── Frida reflection (Shizuku probe) ────────────────────────────────
-keepclasseswithmembers class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# ── Gson (EnvironmentRepository, ApiTestr chain extraction) ─────────
-keepclassmembers class * implements com.google.gson.JsonDeserializer { *; }
-keepclassmembers class * implements com.google.gson.JsonSerializer { *; }
-keepclassmembers class * { @com.google.gson.annotations.SerializedName *; }

# ── libsu (Root access) ─────────────────────────────────────────────
-keepclasseswithmembers class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# ── General keep for annotation processors / code-gen ───────────────
-keepclassmembers class ** { @kotlin.jvm.JvmStatic <methods>; }
-keepclassmembers class ** { @kotlin.jvm.JvmField <fields>; }

# ── OkHttp (API Testr engine) ───────────────────────────────────────
-keepclasseswithmembers class okhttp3.** { *; }
-dontwarn okhttp3.**

# ── Preserve line numbers for crash stack traces ───────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
