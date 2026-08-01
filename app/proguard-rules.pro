-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class com.hermesgadget.talaria.core.network.dto.** { *; }
