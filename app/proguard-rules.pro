# R8 rules for the release build.

# --- kotlinx.serialization ---
# The library ships its own R8 rules; these scope the keeps to our @Serializable models so the
# generated serializers survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class fin.android.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class fin.android.**$$serializer { *; }

# --- Bouncy Castle (Argon2id via the lightweight API) ---
# Used directly (no provider registration); keep the crypto classes and silence its optional deps.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- OkHttp / Okio (ship their own rules; silence optional compile-only deps) ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# --- Tink (via androidx.security.crypto / EncryptedSharedPreferences) ---
# Tink references compile-only Error Prone annotations that aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-keep class com.google.crypto.tink.** { *; }
# Tink's optional remote-keyset downloader pulls google-api-client / joda — unused here.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
