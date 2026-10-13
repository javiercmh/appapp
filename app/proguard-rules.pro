# Proguard rules for AppApp runtime bridge
-keepclassmembers class com.wynh.appapp.bridge.TokenGatedBridge {
    @android.webkit.JavascriptInterface <methods>;
}
