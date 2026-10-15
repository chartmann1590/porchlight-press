# Keep serialization models for kotlinx.serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable Companion;
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
