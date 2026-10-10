# Proguard rules for AppApp runtime bridge
-keepclassmembers class com.wynh.appapp.bridge.NativeStorageBridge {
    @android.webkit.JavascriptInterface <methods>;
}
