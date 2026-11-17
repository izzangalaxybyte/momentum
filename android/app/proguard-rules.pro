# kotlinx.serialization: keep generated serializers for our @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class com.thegalaxybyte.momentum.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.thegalaxybyte.momentum.**$$serializer { *; }
-keepclassmembers class com.thegalaxybyte.momentum.** {
    *** Companion;
}

# WorkManager instantiates workers by class name.
-keep class com.thegalaxybyte.momentum.SyncWorker { <init>(...); }

# OkHttp optional platform classes
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
