-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

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

# Reflection-instantiated components: keep the CLASS NAMES and constructors
# only, not every member — R8 keeps virtual methods reachable through the base
# classes (Worker.doWork, AppWidgetProvider callbacks, TileService callbacks),
# so `{ *; }` here would only bloat the dex and blunt R8 (M12).
#
# WorkManager workers and the boot receiver are instantiated by name.
-keep class com.hermesgadget.talaria.worker.** { <init>(...); }

# Glance widget receivers and Quick Settings tile are reflectively instantiated.
-keep class com.hermesgadget.talaria.widget.** { <init>(...); }

# Android for Cars App Library — hosts instantiate the service/screens
# reflectively; androidx.car.app has its own consumer rules for the rest.
-keep class com.hermesgadget.talaria.car.** { <init>(...); }
-keep class androidx.car.app.** { <init>(...); }
