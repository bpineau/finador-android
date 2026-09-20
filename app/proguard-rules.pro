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
# NO keep rule on purpose. crypto/Argon2.kt calls Argon2BytesGenerator and Argon2Parameters
# DIRECTLY - no JCE provider registration, no Class.forName, no ServiceLoader - so R8 can trace
# exactly what is reachable and discard the rest. A blanket `-keep class org.bouncycastle.**`
# was shipping the whole library: 5757 of the release APK's 9245 classes, including 1501
# post-quantum and 2087 JCA-provider classes that nothing here can ever call.
# -dontwarn stays: bcprov references optional JDK/JCE classes Android does not have, and a
# warning about code R8 is about to remove is noise.
-dontwarn org.bouncycastle.**

