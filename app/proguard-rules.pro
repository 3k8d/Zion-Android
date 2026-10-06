# libbox: the Go side calls these classes by name through JNI
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }

# Saved settings are read back by kotlinx.serialization
-keep class com.zion.app.model.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
