# Proguard rules for AppApp runtime bridge
-keepclassmembers class com.example.runtimecompiler.bridge.NativeStorageBridge {
    @android.webkit.JavascriptInterface <methods>;
}
