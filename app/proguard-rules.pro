# PureCam 混淆规则（release 默认未开启混淆，如需开启请把 isMinifyEnabled 设为 true）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# CameraX 反射相关
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.camera2.internal.**
