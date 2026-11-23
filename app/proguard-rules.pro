# --- Keep metadata reflection-based libraries rely on
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Exceptions
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**


# --- Anthropic SDK (Jackson + Kotlin reflection). Keep members of every
# class that is reachable, but let R8 drop the API areas the app never uses.
-keepclassmembers class com.anthropic.** { *; }
-keep class com.anthropic.core.** { *; }
-keep class com.anthropic.client.okhttp.** { *; }
-keep class com.anthropic.errors.** { *; }
-keep class com.anthropic.models.messages.** { *; }
-keep class com.anthropic.models.models.** { *; }
-keep class com.anthropic.models.ErrorObject** { *; }
-keep class com.anthropic.models.** extends java.lang.Enum { *; }
-dontwarn com.anthropic.**

# Jackson
-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# Misc optional deps referenced by OkHttp / Jackson / the SDK
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.beans.**
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**

# App data classes are (de)serialized by kotlinx.serialization (rules ship with the library).
-keep class com.voicekanban.app.data.** { *; }
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
