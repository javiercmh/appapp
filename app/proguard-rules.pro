# Proguard rules for Runtime Web Compiler
-keepclassmembers class com.example.runtimecompiler.bridge.NativeStorageBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.example.runtimecompiler.bridge.NativeMemoryBridge {
    @android.webkit.JavascriptInterface <methods>;
}
