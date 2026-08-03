-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class com.hermesgadget.talaria.core.network.dto.** { *; }

# Android for Cars App Library — hosts instantiate the service/screens reflectively.
-keep class com.hermesgadget.talaria.car.** { *; }
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**
