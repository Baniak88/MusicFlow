# --- NewPipe Extractor (uses reflection-free code, but Rhino and jsoup need keeps) ---
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-keep class com.google.protobuf.** { *; }

# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

# --- OkHttp ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
