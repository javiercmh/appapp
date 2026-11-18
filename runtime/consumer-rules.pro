# The token-gated bridge is the only @JavascriptInterface object; keep its entry point.
-keepclassmembers class com.wynh.appapp.bridge.TokenGatedBridge {
    @android.webkit.JavascriptInterface <methods>;
}
