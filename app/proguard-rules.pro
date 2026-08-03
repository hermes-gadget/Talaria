-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn retrofit2.**

# kotlinx.serialization models — explicit keeps (library consumer rules cover
# the serializer machinery; these pin the model surface that R8 would otherwise
# strip when reflection is involved).
-keep,includedescriptorclasses class com.hermesgadget.talaria.domain.model.**$$serializer { *; }
-keepclassmembers class com.hermesgadget.talaria.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.hermesgadget.talaria.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager workers and boot receiver are instantiated by name.
-keep class com.hermesgadget.talaria.worker.** { *; }

# Glance widget receivers and Quick Settings tile are reflectively instantiated.
-keep class com.hermesgadget.talaria.widget.** { *; }

# Android for Cars App Library — hosts instantiate the service/screens reflectively.
-keep class com.hermesgadget.talaria.car.** { *; }
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**
