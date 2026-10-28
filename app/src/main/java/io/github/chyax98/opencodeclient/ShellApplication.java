package io.github.chyax98.opencodeclient;

import android.app.Application;
import android.util.Log;

import androidx.webkit.Profile;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewOutcomeReceiver;
import androidx.webkit.WebViewStartUpConfig;
import androidx.webkit.WebViewStartUpResult;
import androidx.webkit.WebViewStartupException;

import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 让 WebView 的启动早一步开始。
 *
 * <p>{@code new WebView()} 是同步的 provider 加载（实测 100–140ms，见 docs/design.md），必须在有 Looper
 * 的 UI 线程做。`WebViewCompat.startUpWebView` 把其中不依赖 UI 线程的那部分先丢到后台线程：Application.onCreate
 * 比 Activity.onCreate 早一点，等 Activity 真要构造第一张 WebView 时，这部分已经在跑了。
 *
 * <p>只对每个进程的第一张 WebView 有意义；不支持的内核/旧 WebView 会自己退化（这里再兜一层，绝不能让
 * 启动预热把 App 弄崩）。
 */
public class ShellApplication extends Application {

    private static final String TAG = "OpenCodeClient";
    private final Executor startupExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        startWebViewEarly();
    }

    private void startWebViewEarly() {
        try {
            WebViewStartUpConfig config = new WebViewStartUpConfig.Builder(startupExecutor)
                    // 这个壳只用默认 Profile：把其余 Profile 的启动任务省掉。
                    .setProfilesToLoadDuringStartup(Collections.singleton(Profile.DEFAULT_PROFILE_NAME))
                    .build();
            // 两个重载都只有一个单参方法，显式转型避免 lambda 歧义。
            WebViewCompat.startUpWebView(this, config,
                    (WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException>)
                            result -> Log.i(TAG, "WebView 启动预热完成"));
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "WebView 启动预热失败（跳过）：" + error);
        }
    }
}
