# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class org.hello.reader.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class org.hello.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit service interfaces and suspend-function continuations.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface org.hello.reader.core.network.StorytellerApi { *; }

# Room KSP entities and DAOs.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class org.hello.reader.core.database.entity.** { *; }
-dontwarn androidx.room.paging.**

# Readium Kotlin Toolkit models and JS bridge classes.
-keep class org.readium.r2.** { *; }
-dontwarn org.readium.r2.**
# joda-time (via Readium) references joda-convert annotations, an optional
# compile-only dependency that is intentionally absent at runtime.
-dontwarn org.joda.convert.**

# AndroidX Media3 & ExoPlayer session / decoder reflection.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# AndroidX Security Crypto / Google Tink keyset serialization.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
