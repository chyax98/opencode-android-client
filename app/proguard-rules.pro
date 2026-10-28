# 正式包（R8）的保守规则。
#
# 壳本身没有反射、也不往 Web UI 注入任何 JS 桥，理论上不 keep 也能过；这里只保两处"万一"：
# zxing 的扫码结果是通过 Intent 传的、CaptureActivity 又由库的 manifest 引用，
# keep 住它避免"release 包里扫码毫无反应"这种只在正式包上出现的事故（代价几百 KB）。

# 以后要是真加了 JS 桥，这条是通用保护：
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.journeyapps.barcodescanner.** { *; }
-keep class com.google.zxing.** { *; }
