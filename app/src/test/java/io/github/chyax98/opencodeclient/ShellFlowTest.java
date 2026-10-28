package io.github.chyax98.opencodeclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.textfield.TextInputLayout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

/**
 * 集成测试：在 JVM 上把 MainActivity + 真正的 WebViewClient 回调跑起来（WebView/CookieManager 是 Robolectric 的 shadow，
 * 加载被记录成 URL，不真的联网）。覆盖模拟器上很难构造的路径：看门狗超时、陈旧回调、多台服务器切换。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ShellFlowTest {

    // 过期时间取未来（2033）：壳现在会本地判 token 过期，夹具用一个 2023 年的"已过期"token 会走进另一条路。
    // 过期那条路由 anExpiredStoredTokenIsNotSeededAndSendsTheUserBackToSetup 专门覆盖。
    private static final String TOKEN = "2000000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";

    private Runnable beforeCreate; // 想在 onCreate 之前装的东西（比如探测的桩）
    private MainActivity activity;
    private WebView web;
    private WebViewClient client;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("opencode", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        createActivity();
    }

    /**
     * 配对现在先走 API 兑换（Redeem）。这两处网络调用与执行器在 JVM 测试里换成桩：默认桩成"网络不通"，
     * 于是自动退回老的"直接加载 /auth/connect/"那条路，原有用例行为不变；要测兑换本身的分支，
     * 在单个用例里改这两个字段即可（真实 HTTP 实现由 RedeemTest 用本地 socket 覆盖）。
     */
    private void stubRedeem() {
        // 崩溃循环标记是 static 的（要跨 recreate 传），测试之间必须归零，否则会漏给下一个用例
        MainActivity.startOnSetup = false;
        activity.redemptionExecutor = Runnable::run;
        // 候选地址探测：默认"什么都没探出来" = 顺序/换代行为和以前一模一样；要测 B 的用例自己改这两个字段
        activity.probeExecutor = Runnable::run;
        activity.probeAll = origins -> new HashMap<>();
        activity.redeemAttempt = (origin, code) -> Redeem.Result.of(Redeem.Status.UNREACHABLE, "no network in test");
        activity.redeemVerify = (origin, token) -> Redeem.Verify.OK;
    }

    // ---------------------------------------------------------------- 连接页的交互

    @Test
    public void aBadLinkIsReportedOnTheFieldWithoutLosingWhatTheUserPasted() {
        EditText field = (EditText) find(setupBox(), EditText.class);
        field.setText("https://a.example/不是配对链接");

        buttonTitled("连接").performClick();

        assertTrue(fieldError().toString(), fieldError().toString().contains("不是 opencode pair"));
        assertEquals("链接有问题时别把他粘的东西清掉：要在这上面改", "https://a.example/不是配对链接",
                field.getText().toString());
        assertTrue("状态行也照旧说一遍", statusText().contains("不是 opencode pair"));

        field.setText("https://a.example/auth/connect/code1"); // 一改就把错误收掉
        assertEquals("", fieldError().toString());
    }

    @Test
    public void theDefaultNameTrimsLongTunnelAddressesButLeavesShortOnesAlone() {
        // 扫出来的隧道地址 48 字符，整条当名字列表就没法看；局域网那种 17 字符原样留着
        assertEquals("192.168.1.2:49374", MainActivity.shortAddress("192.168.1.2:49374"));
        assertEquals("a.example", MainActivity.shortAddress("a.example"));

        String tunnel = "525db95f9b7bcdca.dtbcc7idjdi3.opentunnel.xyz";
        String trimmed = MainActivity.shortAddress(tunnel);
        assertTrue(trimmed, trimmed.startsWith("525db95f…"));
        assertTrue(trimmed, trimmed.endsWith("xyz"));
        assertTrue("截完不能比原来还长：" + trimmed, trimmed.length() <= 22);
        assertTrue(MainActivity.shortAddress("x".repeat(80)).length() <= 22);
    }

    @Test
    public void everyRowHasADiscoverableOverflowMenu() {
        seedServers("https://a.example");
        rebuild();

        Button more = overflowFor("https://a.example");
        assertNotNull("⋮ 得是真图标按钮（不是拿文字凑的）",
                ((com.google.android.material.button.MaterialButton) more).getIcon());
        assertNull("⋮ 不能被当成服务器行（它没有 tag）", more.getTag());
    }

    @Test
    public void serversAreNamedServerOneServerTwoAndTheAddressGoesToTheSmallLine() {
        seedServers("https://a.example"); // 两条都没有名字也没有编号：按列表顺序补上
        rebuild();

        assertEquals("● 服务器 1 · 隧道", buttonFor("https://a.example").getText().toString());
        assertEquals("○ 服务器 2 · 隧道", buttonFor("https://b.example").getText().toString());
        // 小字那行 = 中段省略的短地址；当前那台多一句「· 当前」（不只靠 ● 和描边的颜色）
        assertEquals("a.example · 当前", addressLine("https://a.example"));
        assertEquals("b.example", addressLine("https://b.example"));
    }

    @Test
    public void theOverflowMenuShowsWhichServerAndOffersItsActions() {
        seedServers("https://a.example");
        rebuild();

        overflowFor("https://a.example").performClick(); // 行尾 ⋮

        androidx.appcompat.app.AlertDialog sheet =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("⋮ 应该弹一个动作面板（不是贴行弹、点别处就消失的 PopupMenu）", sheet);
        View panel = sheet.findViewById(R.id.actions_panel);
        assertNotNull(panel);
        String shown = textOf(panel);
        assertTrue("面板得写清楚在操作哪台：" + shown,
                shown.contains("服务器 1") && shown.contains("a.example"));
        assertTrue(shown.contains("改名"));
        assertTrue(shown.contains("复制地址"));
        assertTrue("删除是破坏性的，得显式列出来：" + shown, shown.contains("删除"));
    }

    @Test
    public void thePairingFormIsCollapsedOnceThereAreServersAndReopensFromTheAddAction() {
        seedServers("https://a.example");
        rebuild();

        // 日常进来要的是"切到哪台"，不是"再配一台"：表单先收起来，列表是主角
        assertEquals(View.GONE, activity.findViewById(R.id.setup_field).getVisibility());
        assertEquals(View.GONE, buttonTitled("连接").getVisibility());
        assertEquals("配对提示也跟着表单收起", View.GONE,
                activity.findViewById(R.id.setup_copy_command).getVisibility());

        actionTitled("＋ 添加").performClick();

        assertEquals("点了「＋ 添加」表单得摊开", View.VISIBLE,
                activity.findViewById(R.id.setup_field).getVisibility());
        assertEquals(View.VISIBLE, buttonTitled("连接").getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.setup_copy_command).getVisibility());
        assertEquals("摊开了就换成「收起」", "收起", actionTitled("收起").getText().toString());
    }

    @Test
    public void thePairingHelpShowsTheCommandsAndCopiesThem() {
        String help = textOf(setupBox());
        assertTrue(help, help.contains("opencode pair --remote"));
        assertTrue("码只能用一次这件事得说出来：" + help, help.contains("5 分钟"));
        assertTrue("127.0.0.1 是最容易粘错的一条：" + help, help.contains("127.0.0.1"));

        View copy = activity.findViewById(R.id.setup_copy_command);
        assertNotNull(copy);
        assertEquals(View.VISIBLE, copy.getVisibility());
        copy.performClick();

        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard.getPrimaryClip());
        assertEquals("opencode pair --remote",
                clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("已复制"));
    }

    @Test
    public void withNoServersThePairingFormIsOpenOnFirstRun() {
        // 一台都没有：第一次进来就得知道怎么加，不该让人去猜哪里有入口
        assertEquals(View.VISIBLE, activity.findViewById(R.id.setup_field).getVisibility());
        assertEquals(View.VISIBLE, buttonTitled("扫描二维码").getVisibility());
        assertTrue(textOf(setupBox()).contains("还没有服务器"));
    }

    @Test
    public void aVeryLongTunnelAddressIsElidedOnTheSmallLine() {
        MainActivity.Server server = new MainActivity.Server(
                "https://525db95f9bb7cdca.dtbcc7idjdi3.opentunnel.xyz",
                Collections.singletonList("https://525db95f9bb7cdca.dtbcc7idjdi3.opentunnel.xyz"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", server.key)
                .commit();
        rebuild();

        String line = addressLine(server.key).replace(" · 当前", ""); // 当前那台的小字会多一句「· 当前」
        assertTrue("中段省略、两端保留：" + line, line.contains("…"));
        assertTrue("真机上这条 48 字符的地址把卡片撑成了三行：" + line, line.length() <= 22);
    }

    @Test
    public void switchingBackReusesTheParkedPageWithoutReloading() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        WebView a = web;
        assertEquals("https://a.example", loaded());

        buttonFor("https://b.example").performClick(); // 冷切到 B
        WebView b = (WebView) find(myRoot(), WebView.class);
        assertNotSame("切到另一台不该复用 A 的页面：两页共用一张，切回来还是要重新加载", a, b);
        shadowOf(b).getWebViewClient().onPageFinished(b, "https://b.example/");

        buttonFor("https://a.example").performClick(); // 切回 A：显示停着的那张
        WebView back = (WebView) find(myRoot(), WebView.class);
        assertSame("切回来必须是同一张 WebView，不是重建一张", a, back);
        assertEquals("切回来不许再 loadUrl 一次", "https://a.example", shadowOf(back).getLastLoadedUrl());
        assertEquals(View.VISIBLE, back.getVisibility());
        assertEquals("https://a.example", activeKey());
    }

    @Test
    public void onlyTheOtherServersPreferredAddressIsPreconnected() {
        MainActivity.Server a = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"));
        MainActivity.Server b = new MainActivity.Server("https://b.example",
                Arrays.asList("https://b.example", "http://192.168.1.2:49374"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Arrays.asList(a, b)))
                .putString("active", "https://a.example")
                .commit();
        rebuild();

        // 当前这台不热（已经在用），另一台只热首选地址：局域网那条离开那个网络就不可达
        assertEquals(Collections.singletonList("https://b.example"), activity.preconnectTargets());

        buttonFor("https://b.example").performClick();
        assertEquals(Collections.singletonList("https://a.example"), activity.preconnectTargets());
    }

    @Test
    public void aPageThatNeverLoadedIsNotParked() {
        seedServers("https://a.example");
        rebuild(); // 冷启动那次加载还没完成：A 不算"能用的页面"

        buttonFor("https://b.example").performClick();

        assertEquals("没加载成功的页面不值得留：直接复用同一张 WebView 去加载 B", web,
                find(myRoot(), WebView.class));
        assertEquals("复用同一张 WebView 换 origin 时必须清历史", 1, activity.historyClears);
    }

    @Test
    public void aParkedPageCannotMoveTheLoadingBar() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        WebView a = web;
        WebChromeClient aChrome = shadowOf(a).getWebChromeClient();

        buttonFor("https://b.example").performClick();
        WebView b = (WebView) find(myRoot(), WebView.class);
        shadowOf(b).getWebViewClient().onPageFinished(b, "https://b.example/");
        View bar = activity.findViewById(R.id.load_bar);
        assertEquals(View.GONE, bar.getVisibility());

        aChrome.onProgressChanged(a, 42); // 停着的那张迟到的进度回调

        assertEquals("停着的页面不许驱动当前页的进度条", View.GONE, bar.getVisibility());
    }

    @Test
    public void aQuickSwitchBackDoesNotDisturbTheParkedPage() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        WebView a = web;

        buttonFor("https://b.example").performClick();
        WebView b = (WebView) find(myRoot(), WebView.class);
        shadowOf(b).getWebViewClient().onPageFinished(b, "https://b.example/");
        buttonFor("https://a.example").performClick(); // 几秒内切回来

        assertNull("短时间来回切，SSE 还活着：不许白发一次 online", shadowOf(a).getLastEvaluatedJavascript());
    }

    @Test
    public void aLongParkedPageIsResyncedWhenItComesBack() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        WebView a = web;

        buttonFor("https://b.example").performClick();
        WebView b = (WebView) find(myRoot(), WebView.class);
        shadowOf(b).getWebViewClient().onPageFinished(b, "https://b.example/");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(11)); // 停久了：连接可能半开
        buttonFor("https://a.example").performClick();

        assertEquals("停久了切回来要让页面重订一次事件流",
                "window.dispatchEvent(new Event('online'))", shadowOf(a).getLastEvaluatedJavascript());
    }

    @Test
    public void memoryPressureDropsTheParkedPage() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        WebView a = web;

        buttonFor("https://b.example").performClick();
        WebView b = (WebView) find(myRoot(), WebView.class);
        shadowOf(b).getWebViewClient().onPageFinished(b, "https://b.example/");

        activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW);

        buttonFor("https://a.example").performClick();
        WebView back = (WebView) find(myRoot(), WebView.class);
        assertNotSame("内存紧张时已经丢掉了，切回来只能重新建一张", a, back);
        assertEquals("https://a.example", shadowOf(back).getLastLoadedUrl());
    }

    @Test
    public void aDownloadFromTheTrustedServerGoesThroughTheSystemDownloadManager() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");

        assertNotNull("页面里的 <a download> 得有下载监听，否则点了没反应", shadowOf(web).getDownloadListener());
        shadowOf(web).getDownloadListener().onDownloadStart(
                "https://a.example/files/log.txt", "UA", "attachment; filename=log.txt", "text/plain", 12);

        android.app.DownloadManager manager = (android.app.DownloadManager)
                ApplicationProvider.getApplicationContext().getSystemService(Context.DOWNLOAD_SERVICE);
        assertEquals("可信服务器上的 http(s) 下载交给系统", 1,
                shadowOf(manager).getRequestCount());
    }

    @Test
    public void aBlobDownloadIsRefusedWithAnExplanation() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");

        shadowOf(web).getDownloadListener().onDownloadStart(
                "blob:https://a.example/xyz", "UA", null, "text/plain", 12);

        android.app.DownloadManager manager = (android.app.DownloadManager)
                ApplicationProvider.getApplicationContext().getSystemService(Context.DOWNLOAD_SERVICE);
        assertEquals("没有文件可交给系统，不能假装下载了", 0,
                shadowOf(manager).getRequestCount());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("不支持"));
    }

    @Test
    public void draggingTheBallDoesNotCountAsATap() {
        MainActivity.Server server = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", server.key)
                .commit();
        rebuild();
        client.onPageFinished(web, "https://a.example/");

        View ball = activity.findViewById(R.id.server_ball);
        assertEquals(View.VISIBLE, ball.getVisibility());
        dragBall(ball); // 拖一段再松手（最后一帧离抬手只有 0px，旧写法会判成点击）

        assertNull("拖完不该顺手弹出预览面板", org.robolectric.shadows.ShadowDialog.getLatestDialog());
    }

    @Test
    public void theBallSnapsAndRestoresWithoutDrifting() {
        SharedPreferences ballPrefs = activity.getSharedPreferences("ball-test", Context.MODE_PRIVATE);
        ballPrefs.edit().clear().putFloat("ball_x", 0.5f).putFloat("ball_y", 0.5f).commit();

        FrameLayout host = new FrameLayout(activity);
        int w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY);
        int h = View.MeasureSpec.makeMeasureSpec(2376, View.MeasureSpec.EXACTLY);
        host.measure(w, h);
        host.layout(0, 0, 1080, 2376);
        ServerBall ball = new ServerBall(activity, ballPrefs, () -> {});
        host.addView(ball, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.measure(w, h);
        host.layout(0, 0, 1080, 2376);

        ball.restorePosition(1080, 2376);
        assertEquals("存的是左上角比例，不能当中心点再减半个球", 540f, ball.getX(), 0.5f);

        ball.snapToEdge();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        float snapped = ball.getX();
        float density = activity.getResources().getDisplayMetrics().density;
        float ballSize = dpForTest(48, density);
        float ballMargin = dpForTest(24, density);
        assertEquals("吸附到右边距", 1080 - ballSize - ballMargin, snapped, 1f);

        ball.restorePosition(1080, 2376);
        assertEquals("恢复后还在吸附后的位置（不能每点一次往左上漂 24dp）", snapped, ball.getX(), 0.5f);
    }

    private static float dpForTest(int value, float density) {
        return (int) (value * density + 0.5f);
    }

    @Test
    public void aFreshPairingIsNamedServerTwoWhenServerOneExists() {
        MainActivity.Server only = new MainActivity.Server("https://b.example",
                Collections.singletonList("https://b.example"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(only)))
                .putString("active", only.key)
                .commit();
        rebuild();

        pair("https://a.example/auth/connect/code1");
        client.onPageFinished(web, "https://a.example/");
        activity.onBackPressed(); // 第一次只提示
        activity.onBackPressed(); // 第二次进管理页

        assertEquals("● 服务器 2 · 隧道", buttonFor("https://a.example").getText().toString());
    }

    @Test
    public void repairingKeepsTheNameTheUserGaveIt() {
        seedServers("https://a.example");
        rebuild();
        activity.rename("https://a.example", "我的电脑");

        pair("https://a.example/auth/connect/code1"); // 再配一次同一台（走合并那条路）
        client.onPageFinished(web, "https://a.example/");
        activity.onBackPressed();
        activity.onBackPressed();

        assertEquals("合并时名字不能被丢掉", "我的电脑",
                MainActivity.decodeServers(prefs.getString("servers", "")).get(0).name);
        assertEquals("列表里也还是那个名字", "● 我的电脑 · 隧道",
                buttonFor("https://a.example").getText().toString());
    }

    @Test
    public void theFirstBackAtTheRootOnlyHintsAndTheSecondOpensTheManager() {
        seedServers("https://a.example");
        rebuild();
        assertEquals(View.VISIBLE, web.getVisibility());

        activity.onBackPressed(); // 第一次：只提示，不进管理页（免得有人不知道这个入口）

        assertEquals("第一次不许把人拽走", View.VISIBLE, web.getVisibility());
        assertEquals(1, ShadowToast.shownToastCount());

        activity.onBackPressed(); // 2 秒内再按一次

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertEquals("https://a.example", loaded()); // 不重新加载
    }

    @Test
    public void aRecreateInTheManagerStaysInTheManager() {
        seedServers("https://a.example");
        rebuild();
        activity.onBackPressed();
        activity.onBackPressed(); // 进管理页
        assertEquals(View.VISIBLE, setupBox().getVisibility());

        // fontScale / 旋转那种重建：按 intent 冷启动会把人丢进服务器页面，这里必须还回管理页
        rebuildAfterSave();

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertNull("重建后不许偷偷加载", loaded());
    }

    @Test
    public void switchingRemembersTheRouteOfThePageYouLeft() {
        seedServers("https://a.example");
        rebuild();
        // 在生产里这一步是 Web UI 自己的 pushState（不进 onPause）→ 壳只能在切走前问 web.getUrl()。
        // Robolectric 的影子 WebView 不认 pushState，用 loadUrl 把"当前 URL"摆到那个会话上。
        web.loadUrl("https://a.example/session/ses_7");

        buttonFor("https://b.example").performClick();
        assertEquals("https://b.example", loaded());

        buttonFor("https://a.example").performClick(); // 切回来

        assertEquals("应该回离开时那个会话，而不是上次退后台的旧地址",
                "https://a.example/session/ses_7", loaded());
    }

    @Test
    public void theBallIsTheThirdChildAndOnlyShowsWhileUsingTheWebUi() {
        seedServers("https://a.example");
        rebuild();

        assertEquals("悬浮球是 root 的第三个孩子，第四个是加载进度条（setup 仍必须在 getChildAt(1)，测试探针不许动）",
                4, myRoot().getChildCount());
        assertEquals(setupBox(), myRoot().getChildAt(1));
        assertEquals("加载进度条排在球之后，不能插到 setup 前面",
                R.id.load_bar, myRoot().getChildAt(3).getId());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.server_ball).getVisibility());

        activity.findViewById(R.id.server_ball).performClick(); // 点球：只给预览面板，不直接跳走

        assertEquals("点一下不许直接换页（太容易误触）", View.GONE, setupBox().getVisibility());
        androidx.appcompat.app.AlertDialog preview =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("应该弹出一个预览面板", preview);
        View panel = preview.findViewById(R.id.preview_list);
        assertNotNull("面板里应该是自绘的服务器列表（不是系统单选列表）", panel);
        String shown = textOf(panel);
        assertTrue("条目得带地址，不然两台同一个局域网的机器在面板里分不出谁是谁：" + shown,
                shown.contains("a.example"));
        assertTrue("当前那台要有标记：" + shown, shown.contains("● 服务器 1"));

        activity.openManager(null); // 面板上的「服务器管理」按钮走的是这条（对话框按钮在 Robolectric 里点不到）
        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals("进了管理页球就藏起来（入口就是页面本身）",
                View.GONE, activity.findViewById(R.id.server_ball).getVisibility());
    }

    @Test
    public void aPageLoadShowsAThinProgressBarUntilItFinishes() {
        seedServers("https://a.example");
        rebuild();

        // 冷启动一出去就得看得见：以前这里是一片空白，用户不知道是在连还是已经卡住了
        ProgressBar bar = activity.findViewById(R.id.load_bar);
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertEquals(5, bar.getProgress());
        assertEquals("细线本身得有厚度（3dp），不然 100% 之前基本看不见",
                Math.round(3 * activity.getResources().getDisplayMetrics().density),
                bar.getLayoutParams().height);

        WebChromeClient chrome = shadowOf(web).getWebChromeClient();
        chrome.onProgressChanged(web, 42);
        assertEquals(42, bar.getProgress());
        assertEquals(View.VISIBLE, bar.getVisibility());

        chrome.onProgressChanged(web, 100);
        assertEquals("加载完就收掉，别一直挂在页面顶上", View.GONE, bar.getVisibility());
    }

    @Test
    public void theProgressBarGoesAwayWhenTheConnectPageComesBack() {
        pair("https://a.example/auth/connect/code1");
        assertEquals(View.VISIBLE, activity.findViewById(R.id.load_bar).getVisibility());

        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED"); // 回连接页

        assertEquals(View.GONE, activity.findViewById(R.id.load_bar).getVisibility());
        assertEquals("连接页真的回来了", View.VISIBLE, setupBox().getVisibility());
        assertEquals("页面也真的藏起来了", View.GONE, web.getVisibility());
    }

    @Test
    public void theBallPanelOffersARefreshThatReloadsTheCurrentPage() {
        seedServers("https://a.example");
        rebuild();
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);
        client.onPageFinished(web, route);

        activity.findViewById(R.id.server_ball).performClick();
        androidx.appcompat.app.AlertDialog preview =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View refresh = preview.findViewById(R.id.preview_refresh);
        assertNotNull("面板里得有一个看得见的刷新入口（Web UI 里没有壳这一层）", refresh);
        assertTrue(textOf(refresh), textOf(refresh).contains("重新加载"));
        assertNotNull("面板要能滚：服务器多/大字体/横屏时底部的动作不能被挤出屏幕",
                preview.findViewById(R.id.preview_scroll));
        assertEquals("面板内容确实挂在 ScrollView 里",
                preview.findViewById(R.id.preview_scroll),
                preview.findViewById(R.id.preview_list).getParent());

        refresh.performClick();

        assertEquals("刷新的是当前页面", route, loaded());
        assertEquals("刷新也要让人看见在加载", View.VISIBLE,
                activity.findViewById(R.id.load_bar).getVisibility());
    }

    @Test
    public void theBallPanelCanStartTheScannerToAddAServer() {
        seedServers("https://a.example");
        rebuild();

        activity.findViewById(R.id.server_ball).performClick();
        androidx.appcompat.app.AlertDialog preview =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View scan = preview.findViewById(R.id.preview_scan);
        assertNotNull("面板里要能直接扫码添加一台（不用先回管理页）", scan);
        assertTrue(textOf(scan), textOf(scan).contains("扫码添加"));

        scan.performClick();

        assertNotNull("点下去要拉起扫码页",
                shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test
    public void theBallPanelNavigatesThePageHistoryInsteadOfGoingHome() {
        seedServers("https://a.example");
        rebuild();
        String first = "https://a.example/server/k/session/ses_1";
        String second = "https://a.example/server/k/session/ses_2";
        web.loadUrl(first);
        client.onPageFinished(web, first);
        web.loadUrl(second);
        client.onPageFinished(web, second);

        // 没有上一页：动作压淡且点了不动（不给一个没反应的按钮）
        activity.findViewById(R.id.server_ball).performClick();
        androidx.appcompat.app.AlertDialog preview =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View back = preview.findViewById(R.id.preview_back);
        View forward = preview.findViewById(R.id.preview_forward);
        assertNotNull("面板里要有后退", back);
        assertNotNull("面板里要有前进", forward);
        assertFalse("没有历史时后退必须禁用", back.isEnabled());
        assertFalse("没有历史时前进必须禁用", forward.isEnabled());
        activity.pageBack(); // 没有上一页时调用也不许把页面翻走
        assertEquals("没有历史时不许触发 goBack", 0, shadowOf(web).getGoBackInvocations());
        preview.dismiss();

        // 有上一页：走的是页面自己的历史，不是壳再加载一次首页
        shadowOf(web).setCanGoBack(true);
        activity.findViewById(R.id.server_ball).performClick();
        preview = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        back = preview.findViewById(R.id.preview_back);
        assertTrue("有历史时后退要能点", back.isEnabled());
        activity.pageBack();
        assertEquals(1, shadowOf(web).getGoBackInvocations());
        assertEquals("后退走页面历史，不许换成回首页", second, loaded());

        activity.findViewById(R.id.server_ball).performClick();
        preview = (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        preview.findViewById(R.id.preview_copy).performClick();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("复制的就是当前页面地址", second,
                String.valueOf(clipboard.getPrimaryClip().getItemAt(0).getText()));
        assertTrue(ShadowToast.getTextOfLatestToast().contains("已复制"));
    }

    @Test
    public void afterPairingTheFirstServerTheBallShowsUp() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        // 第一次配对：active 只有在页面真正加载完成时才拿到，球必须在那之后重画出来
        pair("https://a.example/auth/connect/code1");
        assertEquals("页面还没就绪时不显示（那时 active 还是 null）", View.GONE,
                activity.findViewById(R.id.server_ball).getVisibility());

        client.onPageFinished(web, loaded());

        assertEquals("只有一个服务器也得有球：这是回管理页唯一的可见入口", View.VISIBLE,
                activity.findViewById(R.id.server_ball).getVisibility());
    }

    @Test
    public void thePanelSaysHowFarTheLoadHasGot() {
        seedServers("https://a.example");
        rebuild();
        shadowOf(web).getWebChromeClient().onProgressChanged(web, 42);

        activity.findViewById(R.id.server_ball).performClick();
        androidx.appcompat.app.AlertDialog preview =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View panel = preview.findViewById(R.id.preview_list);

        assertTrue("面板里要能读出加载到哪了：" + textOf(panel), textOf(panel).contains("正在加载 42%"));

        // 面板开着的时候进度还在走：文案得跟着变，不然用户盯着一个不动的数字会以为卡死了
        shadowOf(web).getWebChromeClient().onProgressChanged(web, 67);
        assertTrue("面板里的进度要跟着涨：" + textOf(panel), textOf(panel).contains("正在加载 67%"));
    }

    @Test
    public void thePairLinkFieldIsAnOutlinedRingNotAFilledMaterialBox() {
        rebuild();
        TextInputLayout field = (TextInputLayout) activity.findViewById(R.id.setup_field);
        // 上游的输入框是"中性底 + 发丝环，聚焦整圈变蓝"（text-input.css）；filled 那套
        // "灰坑 + 一条 2dp 蓝下划线"是 Material 模板脸，一眼就能看出没包过。
        assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, field.getBoxBackgroundMode());
    }

    @Test
    public void theUndoBarLivesOutsideTheScrollablePage() {
        seedServers("https://a.example");
        rebuild();
        activity.openManager(null);

        buttonFor("https://a.example").performLongClick(); // 长按整行 = 删

        assertEquals(View.VISIBLE, undoRow().getVisibility());
        assertSame("撤销条必须挂在 setupBox 上（滚动容器外面）：跟在列表末尾的话，列表一长它就滚出屏幕了，"
                + "5 秒撤销等于没有", setupBox(), undoRow().getParent());
    }

    @Test
    public void theBallIsHiddenWhenThereIsNoServer() {
        assertEquals("一台都没有时不显示球", View.GONE, activity.findViewById(R.id.server_ball).getVisibility());
    }

    @Test
    public void theUndoOfferExpiresAndThenTheRemovalSticks() {
        seedServers("https://a.example");
        rebuild();

        buttonFor("https://a.example").performLongClick();
        assertEquals(View.VISIBLE, undoRow().getVisibility());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6)); // 超过 5 秒的撤销窗口

        assertEquals(View.GONE, undoRow().getVisibility());
        activity.undoRemove(); // 过期后再点也没用了
        assertEquals("过期就是真的删掉了", 1, serverButtons());
    }

    @Test
    public void pastingAPairingLinkFillsTheFieldAndStartsPairing() {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("link", "https://a.example/auth/connect/code1"));

        activity.pasteLink();

        assertEquals("https://a.example/auth/connect/code1",
                ((EditText) find(setupBox(), EditText.class)).getText().toString());
        assertEquals("粘进能用的链接就该直接开始配对，不用再按一次连接",
                "https://a.example/auth/connect/code1", loaded());
    }

    @Test
    public void theHintUnderTheFieldSaysWhereTheLinkPoints() {
        EditText field = (EditText) find(setupBox(), EditText.class);
        field.setText("http://192.168.1.2:49374/auth/connect/code1");

        assertTrue(helperText().toString(), helperText().toString().contains("将连接到"));
        assertTrue(helperText().toString(), helperText().toString().contains("局域网"));

        field.setText("这不是一条链接");
        assertTrue(helperText().toString(), helperText().toString().contains("不是 opencode pair"));
    }

    @Test
    public void theProgressBarRunsWhilePairingAndStopsWhenTheRedeemIsOver() {
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;

        assertEquals(View.INVISIBLE, progressBar().getVisibility());
        pair("https://a.example/auth/connect/code1");
        assertEquals("配对在飞的时候得看得见", View.VISIBLE, progressBar().getVisibility());

        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("有结论了就不许再转", View.INVISIBLE, progressBar().getVisibility());
    }

    @Test
    public void switchingServersWhilePairingStopsTheProgressBar() {
        seedServers("https://a.example");
        rebuild();
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;

        pair("https://b.example/auth/connect/code1");
        assertEquals(View.VISIBLE, progressBar().getVisibility());

        buttonFor("https://a.example").performClick(); // 用户不等了，切走

        assertEquals("切走了还转就是卡死", View.INVISIBLE, progressBar().getVisibility());
    }

    @Test
    public void aFailedPairingStopsTheProgressBarAndStaysOnTheConnectScreen() {
        // showSetup() 是"配对失败回连接页"那条漏斗：进度条必须在这儿收掉
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;
        activity.redeemAttempt = (origin, code) -> Redeem.Result.of(Redeem.Status.EXPIRED, "401");

        pair("https://a.example/auth/connect/code1");
        assertEquals(View.VISIBLE, progressBar().getVisibility());

        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals("有结论了就不许再转", View.INVISIBLE, progressBar().getVisibility());
        assertTrue(statusText(), statusText().contains("失效"));
    }

    // ---------------------------------------------------------------- 启动

    @Test
    public void bootsToTheConnectScreenWhenNothingIsStored() {
        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertNull(loaded());
    }

    // ---------------------------------------------------------------- 配对

    @Test
    public void pairingLoadsTheOneTimeLinkAndPersistsTheServerOnlyAfterTheUiLoads() {
        pair("https://a.example/auth/connect/code1");

        assertEquals("https://a.example/auth/connect/code1", loaded());
        assertEquals(View.GONE, setupBox().getVisibility());
        assertEquals("还没进 UI，不该落盘", "", prefs.getString("servers", ""));
        assertEquals(null, activeKey());

        client.onPageFinished(web, "https://a.example/");

        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
        assertEquals("https://a.example", activeKey());
    }

    @Test
    public void aPathPrefixedPairingPageIsNotMistakenForSuccess() {
        // 反代把服务挂在 /prefix 下：真实路径是 /prefix/auth/connect/<code>。停在它上面就是失败
        // （只看 pathOf 会漏，把 401 页面当成"进了 UI"并落盘一台用不了的服务器）
        pair("https://host.example/prefix/auth/connect/code1");

        assertEquals("https://host.example/prefix/auth/connect/code1", loaded());
        client.onPageFinished(web, "https://host.example/prefix/auth/connect/code1");

        assertTrue(statusText(), statusText().contains("失效"));
        assertEquals("配对页不算进了 UI", "", prefs.getString("servers", ""));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void aPathPrefixedPairingSucceedsButSaysItProbablyWillNotWork() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);
        activity.redeemVerify = (origin, token) -> Redeem.Verify.OK;
        pair("https://host.example/prefix/auth/connect/code1");
        client.onPageFinished(web, "https://host.example/prefix/");

        // 配对本身能成（我们按带前缀的 origin 兑换），但上游内嵌的 Web UI 固定用 location.origin，
        // 会把 /prefix 丢掉 → 页面自己连不上。必须说出来，别让用户以为能用。
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("路径前缀"));
        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
    }

    @Test
    public void aServiceFailureIsNotReportedAsStartingUp() {
        activity.redeemAttempt = (origin, code) ->
                Redeem.Result.of(Redeem.Status.SERVICE_FAILED, "503 {\"code\":\"service_failed\"}");
        pair("https://a.example/auth/connect/code1");

        // 后端起不来要人去电脑上 service restart，别只说"再试一次"
        assertTrue(statusText(), statusText().contains("service restart"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void anExpiredStoredTokenIsNotSeededAndSendsTheUserBackToSetup() {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Collections.singletonList("https://tunnel.example"));
        server.token = "1700000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF"; // 2023 年就过期了（TTL 30 天）
        server.tokenOrigin = "https://tunnel.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();

        rebuild();

        assertNull("本地就知道过期了，不该再加载页面", loaded());
        assertTrue(statusText(), statusText().contains("配对已过期"));
        assertNull("过期 token 要清掉，别留着假装还连着",
                MainActivity.decodeServers(prefs.getString("servers", "")).get(0).token);
    }

    @Test
    public void aTokenTheServerRejectsIsClearedAndSendsTheUserBackToSetup() {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Collections.singletonList("https://tunnel.example"), "https://tunnel.example/session/ses_1");
        server.token = TOKEN;
        server.tokenOrigin = "https://tunnel.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();
        List<String> verified = new ArrayList<>();
        beforeCreate = () -> activity.redeemVerify = (origin, token) -> {
            verified.add(origin + " " + token);
            return Redeem.Verify.REJECTED;
        };

        rebuild(); // 冷启动照旧先加载（不等这次确认），确认回来才动手
        shadowOf(Looper.getMainLooper()).idle();

        // 只在自己那条 origin 上确认（凭据不送给别的地址）
        assertEquals(Arrays.asList("https://tunnel.example " + TOKEN), verified);
        assertTrue(statusText(), statusText().contains("不认这个会话"));
        assertNull("被服务端拒了就清掉，别把 Web UI 自己的登录屏当成已连接",
                MainActivity.decodeServers(prefs.getString("servers", "")).get(0).token);
    }

    @Test
    public void aRejectedTokenCheckThatLandsAfterSwitchingServersIsIgnored() {
        MainActivity.Server first = new MainActivity.Server("https://tunnel.example",
                Collections.singletonList("https://tunnel.example"));
        first.token = TOKEN;
        first.tokenOrigin = "https://tunnel.example";
        MainActivity.Server second = new MainActivity.Server("https://other.example",
                Collections.singletonList("https://other.example"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Arrays.asList(first, second)))
                .putString("active", "https://tunnel.example")
                .commit();
        List<Runnable> pending = new ArrayList<>();
        beforeCreate = () -> {
            activity.redemptionExecutor = pending::add; // 抓住这次确认，自己决定它什么时候回来
            activity.redeemVerify = (origin, token) -> Redeem.Verify.REJECTED;
        };

        rebuild();
        buttonFor("https://other.example").performClick(); // 用户在确认还没回来时换了服务器
        for (Runnable task : pending) task.run();
        shadowOf(Looper.getMainLooper()).idle();

        // 晚到的"你不认我的 token"属于上一台：不许把用户拽回连接页，也不许清掉别人的凭据
        assertEquals("https://other.example", loaded());
        assertEquals("", statusText());
        assertEquals(TOKEN, MainActivity.decodeServers(prefs.getString("servers", "")).get(0).token);
    }

    @Test
    public void expiredCodeIsReportedAndLeavesNothingStored() {
        pair("https://a.example/auth/connect/code1");
        client.onPageFinished(web, "https://a.example/auth/connect/code1");

        assertTrue(statusText(), statusText().contains("失效"));
        assertEquals("", prefs.getString("servers", ""));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void qrWithSeveralAddressesFallsBackAndALateCallbackCannotOverwriteTheFailure() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://dead.example\",\"http://live.example:4096\"]}");
        assertEquals("https://dead.example/auth/connect/c1", loaded());

        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");
        assertEquals("http://live.example:4096/auth/connect/c1", loaded());

        // 上一次尝试的 onPageFinished 晚到：不许把已经说明的原因覆盖成"码失效"，也不许藏掉正在加载的页面。
        client.onPageFinished(web, "https://dead.example/auth/connect/c1");
        assertEquals("", statusText());
        assertEquals(View.VISIBLE, web.getVisibility());

        client.onPageFinished(web, "http://live.example:4096/");
        MainActivity.Server server = MainActivity.decodeServers(prefs.getString("servers", "")).get(0);
        assertEquals(Arrays.asList("https://dead.example", "http://live.example:4096"), server.origins);
    }

    @Test
    public void watchdogMovesToTheNextAddressWhenNothingRespondsThenGivesUp() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://dead.example\",\"http://live.example:4096\"]}");
        assertEquals("https://dead.example/auth/connect/c1", loaded());

        // 一个响应都没有：20 秒看门狗应该换下一条
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertEquals("http://live.example:4096/auth/connect/c1", loaded());

        // 第二条也没响应：给完就回连接页
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertTrue(statusText(), statusText().contains("等不到响应"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals("", prefs.getString("servers", ""));
    }

    @Test
    public void aPageResponseCancelsTheWatchdog() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://slow.example\"]}");
        client.onPageStarted(web, loaded(), null);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60));

        assertEquals("慢但在正常工作，不该被判超时", "", statusText());
    }

    @Test
    public void aStaleOnPageStartedMustNotCancelTheWatchdog() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://a.example\",\"https://b.example\"]}");

        // 另一条候选（还没轮到它）的事件不许撤掉看门狗
        client.onPageStarted(web, "https://b.example/auth/connect/c1", null);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertEquals("第一条该超时换到下一条", "https://b.example/auth/connect/c1", loaded());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertTrue(statusText(), statusText().contains("等不到响应"));
    }

    @Test
    public void aPairingLinkWithAnExplicitDefaultPortStillConnects() {
        // 配对里写的是 :443，Chromium 回调里不带端口：不归一会让所有回调都匹配不上候选地址。
        pair("https://a.example:443/auth/connect/c1");
        assertEquals("https://a.example/auth/connect/c1", loaded());

        client.onPageFinished(web, "https://a.example/");

        assertEquals("https://a.example", activeKey());
        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
    }

    @Test
    public void aLinkThatIsNotAPairingLinkSaysSo() {
        pair("hello");

        assertTrue(statusText(), statusText().contains("这不是"));
        assertNull(loaded());
    }

    @Test
    public void legacyPairingLinksAreExplainedInsteadOfTried() {
        // 旧版把凭据放在 /connect#<credentials> 里（上游的 legacyPairingLink）：不请求它，也不加载它
        pair("https://a.example/connect#eyJhbGciOiJIUzI1NiJ9.payload.sig");

        assertTrue(statusText(), statusText().contains("旧版"));
        assertNull(loaded());
    }

    // ------------------------------------------------ 配对改走 API 兑换（Redeem）

    @Test
    public void theApiPathTakesTheTokenSetsTheCookieAndSkipsTheOneTimeLink() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        pair("https://a.example/auth/connect/code1");

        // 不再加载 /auth/connect：cookie 已经自己写好了，直接进首页
        assertEquals("https://a.example/", loaded());
        assertTrue("cookie 没写进去：" + cookieFor("https://a.example"),
                cookieFor("https://a.example").contains(TOKEN));

        client.onPageFinished(web, "https://a.example/");
        MainActivity.Server stored = MainActivity.decodeServers(prefs.getString("servers", "")).get(0);
        assertEquals(TOKEN, stored.token);
        assertEquals("https://a.example", stored.tokenOrigin);
    }

    @Test
    public void theAddressThatRedeemsIsTheOneThatGetsTheCookie() {
        List<String> asked = new ArrayList<>();
        activity.redeemAttempt = (origin, code) -> {
            asked.add(origin);
            return Redeem.Result.paired(TOKEN);
        };

        pair("{\"code\":\"c1\",\"urls\":[\"https://tunnel.example\",\"http://192.168.1.2:49374\"]}");

        // 第一条就成，不再去试第二条；cookie 也就不会出现在没参与兑换的地址上
        assertEquals(Arrays.asList("https://tunnel.example"), asked);
        assertEquals("https://tunnel.example/", loaded());
        assertTrue(cookieFor("https://tunnel.example").contains(TOKEN));
        assertNull(cookieFor("http://192.168.1.2:49374"));
    }

    @Test
    public void anExpiredCodeFromTheApiIsReportedWithoutLoadingAnything() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.of(Redeem.Status.EXPIRED, "401");

        pair("https://a.example/auth/connect/code1");

        assertTrue(statusText(), statusText().contains("失效"));
        assertNull(loaded());
        assertEquals("", prefs.getString("servers", ""));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void anAddressThatIsNotOpencodeIsReportedInsteadOfBeingLoaded() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.of(Redeem.Status.NOT_OPENCODE, "text/html");

        pair("https://a.example/auth/connect/code1");

        assertTrue(statusText(), statusText().contains("不是 OpenCode"));
        // 不退回"加载页面"：那只会把一个不相干的网站装进壳里
        assertNull(loaded());
        assertEquals("", prefs.getString("servers", ""));
    }

    @Test
    public void aTokenTheAddressRejectsIsNotAccepted() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);
        activity.redeemVerify = (origin, token) -> Redeem.Verify.REJECTED;

        pair("https://a.example/auth/connect/code1");

        assertTrue(statusText(), statusText().contains("拒绝"));
        assertNull(loaded());
        assertNull("地址拒了就不许写 cookie", cookieFor("https://a.example"));
        assertEquals("", prefs.getString("servers", ""));
    }

    @Test
    public void aVerifyThatOnlyFailedOnTheNetworkStillAcceptsTheToken() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);
        activity.redeemVerify = (origin, token) -> Redeem.Verify.UNREACHABLE;

        pair("https://a.example/auth/connect/code1");

        // 兑换本身是 200 + 形状合法的 token，只是验证那一步网络抖了：先接受，让页面用真实请求说话 ——
        // 否则一次网络抖动就会把用户赶回终端重新生成一个码
        assertEquals("https://a.example/", loaded());
        assertTrue(cookieFor("https://a.example").contains(TOKEN));
    }

    @Test
    public void aSecondConnectWithTheSameCodeWhileOneIsInFlightIsIgnored() {
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        pair("https://a.example/auth/connect/code1");
        pair("https://a.example/auth/connect/code1"); // 同一个码又点一次（双击/又扫了一遍）

        // 码是一次性的：第二次兑换必然 401，晚到的那次会把成功的结果顶掉，所以干脆不发
        assertEquals(1, pending.size());
        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("https://a.example/", loaded());
        assertEquals("", statusText());
    }

    @Test
    public void aRedeemThatLandsAfterTheUserSwitchedServersIsIgnored() {
        seedServers("https://b.example");
        rebuild();
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        pair("https://b.example/auth/connect/code1"); // 兑换开始（被抓住，还没跑）
        buttonFor("https://a.example").performClick(); // 用户在等待期间切到了另一台
        assertEquals("https://a.example", loaded());

        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("https://a.example", loaded()); // 没被晚到的结果拽回 b
        assertNull("也不许给 b 写 cookie", cookieFor("https://b.example"));
    }

    @Test
    public void aRedeemThatLandsAfterTheActivityIsGoneDoesNotTouchTheDeadWebView() {
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add;
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        pair("https://a.example/auth/connect/code1"); // 兑换在飞
        activity.onDestroy(); // 期间 Activity 没了（按返回退出 / 字体大小变更重建）

        // 不抛异常就是通过：这条回调是唯一不经过 WebView 的异步路径，没有守卫就会 NPE
        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void afterASuccessfulRedeemALandingFailureDoesNotReplayTheSpentCode() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);

        pair("{\"code\":\"c1\",\"urls\":[\"https://a.example\",\"http://b.example:4096\"]}");
        assertEquals("https://a.example/", loaded());

        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");

        // 码已经花掉了：第二条地址只重试首页，不拿旧码再请求一次（那会被答成"码失效"，把真原因盖掉）
        assertEquals("http://b.example:4096", loaded());
        assertFalse(statusText(), statusText().contains("失效"));

        client.onPageFinished(web, "http://b.example:4096/");
        MainActivity.Server stored = MainActivity.decodeServers(prefs.getString("servers", "")).get(0);
        assertEquals(TOKEN, stored.token); // 兑换来的 token 照样随服务器落盘
    }

    @Test
    public void aStartingServiceIsReportedInsteadOfLoadingThePage() {
        AtomicInteger attempts = new AtomicInteger();
        activity.redeemAttempt = (origin, code) -> {
            attempts.incrementAndGet();
            return Redeem.Result.of(Redeem.Status.STARTING, "503");
        };

        pair("https://a.example/auth/connect/code1");

        // 服务没起完（503）不是"这地址不是 OpenCode"，也不该退回加载页面白等一次
        assertTrue(statusText(), statusText().contains("启动"));
        assertNull(loaded());
        assertEquals("", prefs.getString("servers", ""));
        assertEquals("码没被花掉：链接要留在输入框里", "https://a.example/auth/connect/code1",
                ((EditText) find(setupBox(), EditText.class)).getText().toString());

        buttonTitled("连接").performClick(); // 用户再点一次 = 用同一条链接真的再兑换一次
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals("不许把刚粘的链接清掉、让人回电脑重新 pair", 2, attempts.get());
    }

    @Test
    public void aRejectedTokenOnTheFirstAddressFallsThroughToTheSecond() {
        activity.redeemAttempt = (origin, code) -> Redeem.Result.paired(TOKEN);
        activity.redeemVerify = (origin, token) -> origin.contains("tunnel")
                ? Redeem.Verify.REJECTED // 隧道那条拒了这个 token
                : Redeem.Verify.OK;

        pair("{\"code\":\"c1\",\"urls\":[\"https://tunnel.example\",\"http://192.168.1.2:49374\"]}");

        // 被拒就换下一条地址试（而不是放弃，或者把凭据硬写进被拒的那条）
        assertEquals("http://192.168.1.2:49374/", loaded());
        assertTrue(cookieFor("http://192.168.1.2:49374").contains(TOKEN));
        assertNull(cookieFor("https://tunnel.example"));
    }

    @Test
    public void aWebPageAddressIsNotLoadedEvenWhenAnotherCandidateIsUnreachable() {
        activity.redeemAttempt = (origin, code) -> origin.startsWith("https://")
                ? Redeem.Result.of(Redeem.Status.UNREACHABLE, "conn refused")
                : Redeem.Result.of(Redeem.Status.NOT_OPENCODE, "text/html");

        pair("{\"code\":\"c1\",\"urls\":[\"https://dead.example\",\"http://notopencode.example\"]}");

        assertTrue(statusText(), statusText().contains("不是 OpenCode"));
        // 只要有一条候选回的是网页，就不退回"加载页面"：那会把不相干的网站装进壳里
        assertNull(loaded());
    }

    @Test
    public void aStoredTokenIsReSeededForAPathPrefixedOrigin() {
        MainActivity.Server paired = new MainActivity.Server("https://tunnel.example/prefix",
                Collections.singletonList("https://tunnel.example/prefix"));
        paired.token = TOKEN;
        paired.tokenOrigin = "https://tunnel.example/prefix";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(paired)))
                .putString("active", "https://tunnel.example/prefix")
                .commit();

        rebuild();

        // cookie 是按 host 存的：tokenOrigin 上那个反代前缀不该挡住重注
        assertTrue("带路径前缀的地址也该能自愈：" + cookieFor("https://tunnel.example"),
                cookieFor("https://tunnel.example").contains(TOKEN));
    }

    @Test
    public void aPagePathPairingDoesNotLeaveAStaleTokenBehind() {
        MainActivity.Server old = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"));
        old.token = TOKEN;
        old.tokenOrigin = "https://a.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(old)))
                .commit();
        rebuild();

        // 默认桩：API 兑换不通 → 退回页面加载那条老路，服务端那边刚种下的是新 cookie
        pair("https://a.example/auth/connect/code2");
        client.onPageFinished(web, "https://a.example/");

        // 存着的那份 token 已经旧了：留着它只会在下次冷启动时把这份新 cookie 盖掉
        assertNull(MainActivity.decodeServers(prefs.getString("servers", "")).get(0).token);
    }

    @Test
    public void aStoredTokenIsReSeededWhenTheCookieJarLostIt() {
        MainActivity.Server paired = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"));
        paired.token = TOKEN;
        paired.tokenOrigin = "https://a.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(paired)))
                .putString("active", "https://a.example")
                .commit();

        rebuild(); // 冷启动，cookie jar 是空的（被系统清过）

        assertTrue("存着的 token 该被重注回去：" + cookieFor("https://a.example"),
                cookieFor("https://a.example").contains(TOKEN));
    }

    @Test
    public void aStoredTokenIsNotSeededOntoAnAddressItDidNotComeFrom() {
        // token 与地址无关，但只有"它是从哪条 origin 兑换来的"才允许写：跨地址写等于把凭据送去别处
        MainActivity.Server paired = new MainActivity.Server("https://tunnel.example",
                Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"));
        paired.token = TOKEN;
        paired.tokenOrigin = "https://tunnel.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(paired)))
                .putString("active", "https://tunnel.example")
                .commit();

        rebuild();
        assertEquals("https://tunnel.example", loaded()); // 先试第一条（token 就是从它来的）

        // 换到第二条地址：它没参与过兑换，不该被种上 token
        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");
        assertEquals("http://192.168.1.2:49374", loaded());
        assertNull(cookieFor("http://192.168.1.2:49374"));
    }

    // ------------------------------------------------ 候选地址探测（B：加载卡住时快速换代）

    /** 两个候选：[死的, 活的]，死在前面——正好是"换了个网络还在撞局域网那条地址"的形状。 */
    private void seedDeadThenAlive() {
        MainActivity.Server server = new MainActivity.Server("https://dead.example",
                Arrays.asList("https://dead.example", "http://live.example:4096"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://dead.example")
                .commit();
    }

    private void stubProbe(String dead, String alive) {
        activity.probeAll = origins -> {
            Map<String, Probe.Liveness> verdicts = new HashMap<>();
            verdicts.put(dead, Probe.Liveness.DEAD);
            verdicts.put(alive, Probe.Liveness.ALIVE);
            return verdicts;
        };
    }

    @Test
    public void aProbedDeadCandidateIsReplacedWithoutWaitingForTheWatchdog() {
        seedDeadThenAlive();
        beforeCreate = () -> stubProbe("https://dead.example", "http://live.example:4096");
        rebuild();

        // 不用推 20 秒时钟：探到连不上就换过去了
        assertEquals("http://live.example:4096", loaded());
        assertEquals("", statusText());
    }

    @Test
    public void aHealthyPreferredAddressStaysPreferredEvenIfAnotherOneAnswers() {
        seedDeadThenAlive();
        // 两条都活着：首选（第一条）仍然是首选 —— 上游 preferFirst 的意图就是"局域网地址离开那个网络就废了，
        // 所以别因为别的地址答得快就换掉它"
        beforeCreate = () -> activity.probeAll = origins -> {
            Map<String, Probe.Liveness> verdicts = new HashMap<>();
            for (String origin : origins) verdicts.put(origin, Probe.Liveness.ALIVE);
            return verdicts;
        };
        rebuild();

        assertEquals("https://dead.example", loaded());
        assertEquals("", statusText());
    }

    @Test
    public void theChainSkipsEveryCandidateTheProbeAlreadyFoundDead() {
        MainActivity.Server server = new MainActivity.Server("https://dead.example",
                Arrays.asList("https://dead.example", "https://also-dead.example", "http://live.example:4096"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://dead.example")
                .commit();
        beforeCreate = () -> activity.probeAll = origins -> {
            Map<String, Probe.Liveness> verdicts = new HashMap<>();
            verdicts.put("https://dead.example", Probe.Liveness.DEAD);
            verdicts.put("https://also-dead.example", Probe.Liveness.DEAD);
            verdicts.put("http://live.example:4096", Probe.Liveness.ALIVE);
            return verdicts;
        };
        rebuild();

        // 中间那条也是死的：一次换代直接越过它，不替它再等一次看门狗
        assertEquals("http://live.example:4096", loaded());
    }

    @Test
    public void aSlowAddressThatCarriesOurSessionIsNotAbandoned() {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"));
        server.token = TOKEN;
        server.tokenOrigin = "https://tunnel.example";
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();
        beforeCreate = () -> stubProbe("https://tunnel.example", "http://192.168.1.2:49374");
        rebuild();

        // 探到"连不上"的那条正好带着我们的会话（凭据只对它有效）：可能只是慢，宁可等
        assertEquals("https://tunnel.example", loaded());
        assertEquals("", statusText());

        // 看门狗照旧兜底：真不响应的话还是会换到下一条（= 不做 B 时的行为）
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertEquals("http://192.168.1.2:49374", loaded());
    }

    @Test
    public void aDeadAddressWithNoAlternativeIsLeftToTheWatchdog() {
        seedDeadThenAlive();
        // 两条都探不到：换过去也是死胡同，别乱动，让看门狗按老规矩来
        beforeCreate = () -> activity.probeAll = origins -> {
            Map<String, Probe.Liveness> verdicts = new HashMap<>();
            for (String origin : origins) verdicts.put(origin, Probe.Liveness.DEAD);
            return verdicts;
        };
        rebuild();

        assertEquals("https://dead.example", loaded());
        assertEquals("", statusText());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertTrue(statusText(), statusText().contains("等不到响应")); // 看门狗照旧把链走完并说明原因
    }

    @Test
    public void aVerdictThatArrivesAfterThePageStartedChangesNothing() {
        seedDeadThenAlive();
        List<Runnable> pending = new ArrayList<>();
        beforeCreate = () -> {
            stubProbe("https://dead.example", "http://live.example:4096");
            activity.probeExecutor = pending::add;
        };
        rebuild();
        String current = loaded();
        assertEquals("https://dead.example", current);
        client.onPageStarted(web, current, null); // 页面开始响应了：看门狗撤掉

        pending.get(0).run(); // 探测这时候才跑完
        shadowOf(Looper.getMainLooper()).idle();

        // 已经开始渲染的地址不动它："慢"不等于"坏"
        assertEquals(current, loaded());
        assertEquals("", statusText());
    }

    @Test
    public void aProbeThatLandsAfterTheUserSwitchedServersIsIgnored() {
        MainActivity.Server probeMe = new MainActivity.Server("https://dead.example",
                Arrays.asList("https://dead.example", "http://live.example:4096"));
        // 新服务器故意也带一个"上一轮探到死的"地址：晚到的探测结果要是写进状态，
        // 新一次尝试就会把这条地址当死的跳过（这里正因为共享地址才看得出来）
        MainActivity.Server other = new MainActivity.Server("https://other.example",
                Arrays.asList("https://other.example", "https://dead.example"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Arrays.asList(probeMe, other)))
                .putString("active", "https://dead.example")
                .commit();
        List<Runnable> pending = new ArrayList<>();
        beforeCreate = () -> {
            stubProbe("https://dead.example", "http://live.example:4096");
            activity.probeExecutor = pending::add; // 抓住探测任务，自己决定什么时候让它回来
        };
        rebuild();
        assertEquals(1, pending.size());

        buttonFor("https://other.example").performClick(); // 用户在探测还没回来时换了服务器
        assertEquals("https://other.example", loaded());

        pending.get(0).run();
        shadowOf(Looper.getMainLooper()).idle();

        // 晚到的探测结果属于上一台：不许把用户拽回去，也不许污染新一次尝试的候选链
        assertEquals("https://other.example", loaded());
        activity.onLoadFailed("https://other.example", "net::ERR_CONNECTION_REFUSED");
        assertEquals("https://dead.example", loaded());
    }

    @Test
    public void aSingleAddressServerIsNotProbed() {
        seedServers("https://a.example"); // 只有一个地址
        AtomicInteger probes = new AtomicInteger();
        beforeCreate = () -> activity.probeAll = origins -> {
            probes.incrementAndGet();
            return new HashMap<>();
        };
        rebuild();

        assertEquals("没什么可挑的就不该探测", 0, probes.get());
        assertEquals("https://a.example", loaded());
    }

    private String cookieFor(String url) {
        return CookieManager.getInstance().getCookie(url);
    }

    // ---------------------------------------------------------------- 结论位的边界

    @Test
    public void lateSuccessAfterTheLastCandidateFailedDoesNotPersist() {
        pair("https://a.example/auth/connect/code1");
        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");
        assertTrue(statusText(), statusText().contains("连不上这台电脑"));
        assertEquals("", prefs.getString("servers", ""));

        client.onPageFinished(web, "https://a.example/"); // 同一候选、晚到的成功

        assertEquals("失败不许被晚到的回调改成已连接", "", prefs.getString("servers", ""));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    // ---------------------------------------------------------------- 回来以后还新不新

    @Test
    public void comingBackFromTheBackgroundAfterAWhileAsksThePageToResubscribe() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded()); // 页面已经能用

        activity.onPause();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        activity.onResume();

        assertEquals("锁屏/切出去一阵再回来：让 Web UI 重新订阅一次事件流（不重载页面）",
                "window.dispatchEvent(new Event('online'))", shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test
    public void aQuickTripToTheBackgroundDoesNotPushAnythingIntoThePage() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());

        activity.onPause();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        activity.onResume();

        assertNull("只是切出去两秒（很多操作都会 pause）：别每回都打断一次连接",
                shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test
    public void theResubscribeWaitsUntilTheWebUiIsVisibleAgain() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());
        activity.openManager(null); // 人在管理页，页面被藏起来了

        activity.onPause();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        activity.onResume();

        assertNull("管理页上没有正在看的页面，不该往后台页面里塞事件",
                shadowOf(web).getLastEvaluatedJavascript());
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void aMidSessionFailureRetriesInPlaceBeforeEjectingToTheManager() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route); // Web UI 自己 pushState/点链接（shadow 只记录 URL）

        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED");

        assertTrue("得让人知道在重试", ShadowToast.getTextOfLatestToast().contains("正在重试"));
        assertEquals("不能把人踢回管理页", View.GONE, setupBox().getVisibility());
        assertEquals(View.VISIBLE, web.getVisibility());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
        assertEquals("重试的是同一个页面", route, loaded());

        // 重试真的开始导航了（failedUrl 在这一步才解开），然后它也失败
        client.onPageStarted(web, route, null);
        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED"); // 这才走原来的失败路径
        assertTrue(statusText(), statusText().contains("连不上这台电脑"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void aLateFinishFromTheFailedLoadIsNotMistakenForTheRetrySucceeding() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);

        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED"); // 进入"原地重试"的窗口
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); // 重试发出去
        // 失败那次迟到的完成（在重试真正开始导航之前到）：不许被当成重试的成功
        client.onPageFinished(web, route);
        client.onPageStarted(web, route, null);
        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED");

        // 迟到那次要是被当成成功，pageRetries 会归零 → 这里会再排一次重试，而不是回连接页
        assertTrue(statusText(), statusText().contains("连不上这台电脑"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void goingBackToTheManagerCancelsThePendingRetry() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);

        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED");
        activity.openManager(null); // 这 800ms 里自己按了返回
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));

        assertEquals("人已经在管理页了，别把他拽回页面", View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());

        buttonFor("https://a.example").performClick(); // 再点当前那台

        assertEquals("排队的重试被取消后页面停在错误页上：点当前那台必须重连", route, loaded());
        assertEquals("重连要看得见（错误页原样翻出来时进度线是收起的）",
                View.VISIBLE, activity.findViewById(R.id.load_bar).getVisibility());
    }

    @Test
    public void goingBackToTheManagerDuringTheRetryAlsoMarksThePageDead() {
        seedServers("https://a.example");
        rebuild();
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);
        client.onPageFinished(web, route);

        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED"); // 排 800ms 重试
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800)); // 重试已经发出、还没结果
        activity.openManager(null); // 这期间进了管理页
        buttonFor("https://a.example").performClick();

        assertEquals("重试中途回管理页：回来时也要重连，别把中间态翻出来",
                route, loaded());
        assertEquals("重连要看得见", View.VISIBLE,
                activity.findViewById(R.id.load_bar).getVisibility());
    }

    @Test
    public void aFailedUrlThatIsAPrefixOfTheNextCandidateCannotPenetrate() {
        // 候选互为路径前缀（--url 带前缀那种）时，新候选的 base 能"前缀匹配"上旧候选的 URL，
        // 所以只能靠 failedUrl 把旧候选的迟到回调挡住。
        pair("{\"code\":\"c1\",\"urls\":[\"https://t.example/prefix\",\"https://t.example\"]}");
        assertEquals("https://t.example/prefix/auth/connect/c1", loaded());

        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");
        assertEquals("https://t.example/auth/connect/c1", loaded());

        client.onPageFinished(web, "https://t.example/prefix/auth/connect/c1"); // 旧候选的迟到成功

        assertEquals("旧候选的迟到成功不许落盘", "", prefs.getString("servers", ""));
        assertEquals("也不许被当成码失效打回连接页", View.VISIBLE, web.getVisibility());
        assertEquals("", statusText());

        client.onPageFinished(web, "https://t.example/"); // 新候选自己的成功才算数
        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
        // key 记的是首选地址（列表里第一条），跟这次成功用的是哪条候选无关
        assertEquals("https://t.example/prefix", activeKey());
    }

    @Test
    public void aLateCallbackForAFailedUrlDoesNotDisarmTheWatchdog() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://t.example/prefix\",\"https://t.example\"]}");
        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED");
        assertEquals("https://t.example/auth/connect/c1", loaded());

        client.onPageStarted(web, "https://t.example/prefix/auth/connect/c1", null); // 旧候选迟到的事件

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertTrue("迟到回调不该撤掉新加载的看门狗", statusText().contains("等不到响应"));
    }

    @Test
    public void aLateCallbackForAFailedRouteDoesNotTouchTheHomeLoad() {
        // 路由落在候选 0（默认隧道）上：4 秒超时回退到首页后，那条路由的迟到回调不许动首页那次加载。
        seedRouteOnPreferredCandidate("https://tunnel.example/session/ses_9");
        rebuild();
        assertEquals("https://tunnel.example/session/ses_9", loaded());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5)); // 路由那次超时 → 回首页
        assertEquals("https://tunnel.example", loaded());
        assertEquals("https://tunnel.example", activeKey());

        activity.onLoadFailed("https://tunnel.example/session/ses_9", "net::ERR_ABORTED"); // 迟到的错误
        client.onPageFinished(web, "https://tunnel.example/session/ses_9");                // 迟到的完成

        assertEquals("不许被当成失败提示", "", statusText());
        assertEquals("也不许被当成成功落盘", "https://tunnel.example", activeKey());
        assertEquals(View.VISIBLE, web.getVisibility());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(21));
        assertTrue("首页那次加载的看门狗必须还在", statusText().contains("等不到响应"));
    }

    @Test
    public void backingOutDuringPairingIgnoresTheLateSuccess() {
        List<Runnable> pending = new ArrayList<>();
        activity.redemptionExecutor = pending::add; // 抓住这次兑换，让它真的"在飞"
        pair("https://a.example/auth/connect/code1");
        assertEquals(View.VISIBLE, progressBar().getVisibility());

        activity.onBackPressed(); // 配对在飞时一次返回 = 取消这次配对，不是退出 App

        assertEquals("不许把 App 也退了", false, activity.isFinishing());
        assertEquals(View.INVISIBLE, progressBar().getVisibility());
        assertEquals(View.VISIBLE, setupBox().getVisibility());

        pending.get(0).run(); // 晚到的成功
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("取消后不许落盘", "", prefs.getString("servers", ""));
        assertNull("也不许偷偷加载", loaded());
    }

    @Test
    public void theDeleteOfTheActiveServerStaysInTheManagerAndCanBeUndone() {
        seedServers("https://a.example");
        rebuild();
        assertEquals("https://a.example", loaded());

        buttonFor("https://a.example").performLongClick();

        assertEquals("列表里少了一台", 1, serverButtons());
        assertEquals("当前落到剩下那台", "https://b.example", activeKey());
        assertEquals("但不许把人拽进他没点过的页面", View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertEquals("https://a.example", loaded()); // 没有发起新的加载

        activity.undoRemove();

        assertEquals("撤销把条目放回去", 2, serverButtons());
        assertEquals("连'当前'一起还回去", "https://a.example", activeKey());
        assertEquals("撤销也不加载：留在管理页", View.VISIBLE, setupBox().getVisibility());
        assertEquals("https://a.example", loaded());
    }

    @Test
    public void subframeErrorsAreIgnored() {
        pair("https://a.example/auth/connect/c1");

        // 非主框架：回调第一行就返回，连 error/response 都不会碰（所以这里可以给 null）
        client.onReceivedError(web, request(loaded(), false), null);
        client.onReceivedHttpError(web, request(loaded(), false), null);

        assertEquals("https://a.example/auth/connect/c1", loaded());
        assertEquals("", statusText());
    }

    @Test
    public void a4xxThenTheConnectPageIsReportedAsExpired() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://a.example\"]}");

        activity.onHttpError(loaded(), 404); // 4xx 不换地址，交给页面
        client.onPageFinished(web, loaded());

        assertTrue(statusText(), statusText().contains("失效"));
    }

    @Test
    public void http5xxOnTheAppItselfDoesNotKillTheLiveSession() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        activity.onHttpError("https://a.example/session/x", 503);

        assertEquals("已经在用的 UI 不该被打回连接页", View.VISIBLE, web.getVisibility());
        assertEquals("https://a.example", activeKey());
        assertEquals("", statusText());
    }

    @Test
    public void aColdLoadErrorPageIsNotMistakenForAConnectedPage() {
        seedServers("https://a.example");
        rebuild();

        // 隧道通了、后面的 opencode 没起来：Cloudflare 回一个 502 错误页。
        activity.onHttpError(loaded(), 502);
        client.onPageFinished(web, loaded()); // Chromium 随后补的 load 事件不能再把它翻成成功

        assertEquals("错误页必须回连接页，不能留在 WebView 里假装连上了", View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertTrue(statusText(), statusText().contains("502"));
        assertTrue("要说清是哪一层坏了：" + statusText(), statusText().contains("opencode"));
        assertEquals("https://a.example", activeKey());
    }

    @Test
    public void aColdLoad401SaysThePairingIsGone() {
        seedServers("https://a.example");
        rebuild();

        activity.onHttpError(loaded(), 401);

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertTrue(statusText(), statusText().contains("opencode pair"));
    }

    @Test
    public void a401OnALivePageWarnsOnceWithoutKickingTheUserOut() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");
        ShadowToast.reset();

        activity.onHttpError("https://a.example/session/x", 401);
        activity.onHttpError("https://a.example/session/y", 401);

        assertEquals("页面照旧留着，只是提醒登录被撤销了", View.VISIBLE, web.getVisibility());
        assertEquals("同一件事只说一次", 1, ShadowToast.shownToastCount());
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("重新配对"));
    }

    @Test
    public void aNetworkFailureIsExplainedInsteadOfShowingTheRawWebViewCode() {
        seedServers("https://a.example");
        rebuild();

        activity.onLoadFailed(loaded(), "net::ERR_CONNECTION_REFUSED", Failure.CONNECT);

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertTrue("要指到电脑和服务，而不是甩一个 net:: 前缀：" + statusText(),
                statusText().contains("开着机") && statusText().contains("opencode"));
    }

    @Test
    public void noNetworkAtStartSaysSoImmediatelyAndNeverTouchesTheWebView() {
        beforeCreate = () -> {
            seedServers("https://a.example");
            ConnectivityManager manager = ApplicationProvider.getApplicationContext()
                    .getSystemService(ConnectivityManager.class);
            shadowOf(manager).setDefaultNetworkActive(false);
        };
        rebuild();

        assertNull("没网时不发起加载（也不用一条条等看门狗）", loaded());
        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertTrue(statusText(), statusText().contains("手机没有网络"));
    }

    @Test
    public void aFailureWhileOfflineDoesNotWasteTheOneRetry() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");
        ConnectivityManager manager = activity.getSystemService(ConnectivityManager.class);
        shadowOf(manager).setDefaultNetworkActive(false);

        activity.onLoadFailed("https://a.example/", "net::ERR_INTERNET_DISCONNECTED", Failure.UNKNOWN);

        assertEquals("没网还重试一次只会多等一轮：直接回连接页说明原因", View.VISIBLE, setupBox().getVisibility());
        assertTrue(statusText(), statusText().contains("手机没有网络"));
    }

    @Test
    public void switchingToTheNextCandidateSaysWhichAddressItIsTrying() {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();
        rebuild();
        ShadowToast.reset();

        activity.onLoadFailed("https://tunnel.example", "net::ERR_CONNECTION_TIMED_OUT", Failure.TIMEOUT);

        assertEquals("http://192.168.1.2:49374", loaded());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("换地址是壳自己的动作，用户得知道发生了什么", toast);
        assertTrue(toast, toast.contains("第 2 条"));
        assertTrue("说形态比长地址更容易认：" + toast, toast.contains("局域网"));
    }

    @Test
    public void aColdSwitchRecordsWhenThePageIsActuallyVisible() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, "https://a.example/");
        ShadowLog.clear();

        buttonFor("https://b.example").performClick();
        WebView b = (WebView) find(myRoot(), WebView.class);
        shadowOf(b).getWebViewClient().onPageCommitVisible(b, "https://b.example");

        boolean painted = false;
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("OpenCodeClient")) {
            if (item.msg.contains("切台：冷切可见")) painted = true;
        }
        assertTrue("切台日志要打在真正画出来的那一刻，不是 load 事件上", painted);
    }

    // ---------------------------------------------------------------- 上次的路由

    @Test
    public void aStoredRouteOnASecondaryAddressIsStillRestored() {
        seedRouteOnSecondaryCandidate("http://192.168.1.2:49374/session/ses_9");
        rebuild();

        assertEquals("http://192.168.1.2:49374/session/ses_9", loaded());
    }

    @Test
    public void aRouteThatNeverAnswersFallsBackToThePreferredAddressHome() {
        seedRouteOnSecondaryCandidate("http://192.168.1.2:49374/session/ses_9");
        rebuild();
        assertEquals("http://192.168.1.2:49374/session/ses_9", loaded());
        ShadowToast.reset();

        // 试路由用的是短超时：连不上就直接回首选地址首页，不用干等 20 秒
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));

        assertEquals("https://tunnel.example", loaded());
        assertEquals("https://tunnel.example", activeKey());
        String toast = ShadowToast.getTextOfLatestToast();
        assertNotNull("回首页要说一声，别让人以为上次停的地方被清掉了", toast);
        assertTrue(toast, toast.contains("首页"));
    }

    @Test
    public void rememberRouteIgnoresJunk() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        activity.rememberRoute(null);
        activity.rememberRoute("about:blank");
        assertNull(storedRoute());

        activity.rememberRoute("https://a.example/session/ses_7");
        assertEquals("https://a.example/session/ses_7", storedRoute());
        activity.rememberRoute("https://a.example/session/ses_7"); // 再来一次不炸
        assertEquals("https://a.example/session/ses_7", storedRoute());
    }

    @Test
    public void repeatedRendererCrashesEndAtTheConnectScreenInsteadOfFlickering() {
        seedRouteOnPreferredCandidate("https://tunnel.example/session/ses_9");
        rebuild();

        activity.onRenderGone(true);
        activity.onRenderGone(true);
        activity.onRenderGone(true); // 连续三次 → 别再自动回到那个页面

        assertNull("崩溃循环时要把 route 清掉，否则下次又回同一个页面", storedRoute());
    }

    @Test
    public void aSingleRendererCrashJustRebuilds() {
        seedRouteOnPreferredCandidate("https://tunnel.example/session/ses_9");
        rebuild();

        activity.onRenderGone(false);

        assertEquals("偶发一次不该动 route", "https://tunnel.example/session/ses_9", storedRoute());
    }

    // ---------------------------------------------------------------- 边角

    @Test
    public void theServerRowShowsItsNameAndWhatKindOfAddressItIs() {
        seedServers("https://a.example");
        rebuild();

        // 第一行：默认名字（服务器 N）+ 这条会话落脚的地址是哪一类；完整地址退到第二行小字
        assertEquals("● 服务器 1 · 隧道", buttonFor("https://a.example").getText().toString());
        assertTrue(addressLine("https://a.example"), addressLine("https://a.example").contains("a.example"));
        assertTrue("当前那台不用颜色区分也不认错：实心点 + 卡片描边",
                buttonFor("https://b.example").getText().toString().startsWith("○ "));
    }

    @Test
    public void renamingAServerPersistsAndShowsUpInTheList() {
        MainActivity.Server server = new MainActivity.Server("https://192.168.1.2:49374",
                Collections.singletonList("https://192.168.1.2:49374"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", server.key)
                .commit();
        rebuild();

        activity.rename(server.key, "  我的电脑  ");

        assertEquals("我的电脑", MainActivity.decodeServers(prefs.getString("servers", "")).get(0).name);
        assertTrue(buttonFor(server.key).getText().toString().contains("我的电脑"));

        activity.rename(server.key, "   ");
        assertNull("清空就恢复成自动名字，别留个空名字", MainActivity.decodeServers(prefs.getString("servers", "")).get(0).name);
        assertTrue("清空后回到「服务器 N」，地址仍然在第二行小字里",
                buttonFor(server.key).getText().toString().contains("服务器 1"));
        assertTrue(addressLine(server.key), addressLine(server.key).contains("192.168.1.2"));
    }

    @Test
    public void theKindLabelIsDerivedFromTheAddressItself() {
        assertEquals("隧道", MainActivity.addressLabel("https://route.tunnel.example"));
        assertEquals("隧道", MainActivity.addressLabel("https://host.example/prefix"));
        assertEquals("局域网", MainActivity.addressLabel("http://192.168.1.2:49374"));
        assertEquals("局域网", MainActivity.addressLabel("http://10.0.0.5:49374"));
        assertEquals("局域网", MainActivity.addressLabel("http://172.16.3.4:49374"));
        assertEquals("局域网", MainActivity.addressLabel("http://[fd00::1]:49374"));
        assertEquals("本机", MainActivity.addressLabel("http://127.0.0.1:49374"));
        assertEquals("本机", MainActivity.addressLabel("http://localhost:49374"));
        assertEquals("直连", MainActivity.addressLabel("http://vps.example:49374"));
        assertEquals("直连", MainActivity.addressLabel("http://8.8.8.8:49374"));
        assertEquals("地址", MainActivity.addressLabel(null));
    }

    @Test
    public void forgettingTheLastServerReturnsToAnEmptyConnectScreen() {
        seedRoute("https://a.example/session/x");
        rebuild();

        buttonFor("https://a.example").performLongClick();

        assertEquals(0, serverButtons());
        assertNull(activeKey());
        assertTrue(statusText(), statusText().contains("已删除"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void aStaleActiveKeyFallsBackToTheFirstStoredServer() {
        seedServers("https://gone.example");
        rebuild();

        assertEquals("https://a.example", loaded());
    }

    @Test
    public void trustIsTableDrivenOnRealUrls() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        String[][] cases = {
                {"https://a.example/session/x", "false"},
                {"https://a.example.evil.com/", "true"},
                {"https://evil.com/?next=https://a.example", "true"},
                {"https://a.example@evil.com/", "true"},
                {"http://a.example/", "true"},
                {"https://a.example:8443/", "true"},
                {"mailto:a@b.example", "true"},
                {"javascript:alert(1)", "true"},
        };
        for (String[] c : cases) {
            boolean leave = Boolean.parseBoolean(c[1]);
            assertEquals(c[0], leave, client.shouldOverrideUrlLoading(web, request(c[0])));
            if (leave) {
                assertNotNull(c[0], shadowOf(activity).getNextStartedActivity());
            } else {
                assertNull(c[0], shadowOf(activity).getNextStartedActivity());
            }
        }
    }

    @Test
    public void aRedirectToAnUntrustedOriginIsPushedOutInsteadOfRendered() {
        pair("https://a.example/auth/connect/c1"); // 会话还没建起来

        // 302 不走 shouldOverrideUrlLoading，只能在 onPageStarted 兜住
        client.onPageStarted(web, "https://evil.example/catch", null);

        Intent external = shadowOf(activity).getNextStartedActivity();
        assertNotNull(external);
        assertEquals("https://evil.example/catch", String.valueOf(external.getData()));
        assertTrue(statusText(), statusText().contains("已交给系统浏览器"));
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    @Test
    public void aRedirectWhileTheSessionIsLiveDoesNotKickTheUserOut() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/"); // 已经在用 UI 了

        client.onPageStarted(web, "https://evil.example/catch", null);

        assertNotNull("外站还是交给系统浏览器", shadowOf(activity).getNextStartedActivity());
        assertEquals("用着好好的，别被打回连接页", View.VISIBLE, web.getVisibility());
        assertEquals("", statusText());
    }

    @Test
    public void windowOpenIsPushedOutThroughTheProbeWebView() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        WebView host = new WebView(activity);
        WebView.WebViewTransport transport = host.new WebViewTransport();
        Message message = Message.obtain(new Handler(Looper.getMainLooper()));
        message.obj = transport;
        assertTrue(shadowOf(web).getWebChromeClient().onCreateWindow(web, false, true, message));

        WebView probe = transport.getWebView();
        assertNotNull(probe);
        assertTrue(shadowOf(probe).getWebViewClient()
                .shouldOverrideUrlLoading(probe, request("https://oauth.example/authorize")));

        Intent external = shadowOf(activity).getNextStartedActivity();
        assertNotNull(external);
        assertEquals("https://oauth.example/authorize", String.valueOf(external.getData()));
        assertTrue("拿到地址就收掉探针，别让它留着", shadowOf(probe).wasDestroyCalled());
    }

    @Test
    public void aWindowWithoutAUrlIsDestroyedAfterTheGracePeriod() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        WebView host = new WebView(activity);
        WebView.WebViewTransport transport = host.new WebViewTransport();
        Message message = Message.obtain(new Handler(Looper.getMainLooper()));
        message.obj = transport;
        assertTrue(shadowOf(web).getWebChromeClient().onCreateWindow(web, false, true, message));
        WebView probe = transport.getWebView();
        assertNotNull(probe);

        shadowOf(probe).getWebViewClient().onPageFinished(probe, "about:blank");
        assertFalse("1.5 秒宽限期内先别动（JS 可能还要设 location）",
                shadowOf(probe).wasDestroyCalled());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        assertTrue("没人设地址的 window.open 必须收掉，不然每次都漏一个 WebView",
                shadowOf(probe).wasDestroyCalled());
    }

    @Test
    public void cancellingTheFileChooserSettlesTheCallback() {
        AtomicInteger settled = new AtomicInteger();
        AtomicReference<Uri[]> picked = new AtomicReference<>();
        assertTrue(shadowOf(web).getWebChromeClient()
                .onShowFileChooser(web, value -> {
                    picked.set(value);
                    settled.incrementAndGet();
                }, chooserParams()));

        activity.onActivityResult(0x5f3, Activity.RESULT_CANCELED, null);

        // 必须"确实被结掉"：只断言 null 分不出"结成 null"和"从来没结"
        assertEquals("取消后回调必须结掉，否则 <input type=file> 再也不响应", 1, settled.get());
        assertNull(picked.get());
    }

    @Test
    public void aSecondFileChooserSettlesTheFirstOneAndDestroySettlesTheRest() {
        AtomicInteger firstSettled = new AtomicInteger();
        AtomicInteger secondSettled = new AtomicInteger();
        WebChromeClient chrome = shadowOf(web).getWebChromeClient();

        chrome.onShowFileChooser(web, value -> firstSettled.incrementAndGet(), chooserParams());
        chrome.onShowFileChooser(web, value -> secondSettled.incrementAndGet(), chooserParams());

        assertEquals("来新请求时上一个必须立刻结掉", 1, firstSettled.get());
        assertEquals(0, secondSettled.get());

        activity.onDestroy();
        assertEquals("销毁时剩下的那个也要结掉", 1, secondSettled.get());
    }

    @Test
    public void cameraPermissionIsOnlyNeededForVideoCapture() {
        assertTrue(MainActivity.needsCamera(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}));
        assertFalse(MainActivity.needsCamera(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}));
        assertFalse(MainActivity.needsCamera(new String[0]));
    }

    @Test
    public void aNewIntentWithoutTheScreenExtraKeepsTheWebView() {
        pair("https://a.example/auth/connect/c1");
        client.onPageFinished(web, "https://a.example/");

        activity.onNewIntent(new Intent(Intent.ACTION_VIEW));

        assertEquals(View.VISIBLE, web.getVisibility());
        assertEquals("https://a.example", activeKey());
    }

    @Test
    public void loopbackLinkIsRefusedWithAnActionableHint() {
        pair("http://127.0.0.1:49374/auth/connect/abc");

        assertTrue(statusText(), statusText().contains("本机"));
        assertNull(loaded());
        assertEquals(View.VISIBLE, setupBox().getVisibility());
    }

    // ---------------------------------------------------------------- 多台服务器

    @Test
    public void storedServersAreListedAndSwitchable() {
        seedServers("https://b.example");
        rebuild();

        assertEquals("https://b.example", loaded());
        assertEquals("https://b.example", activeKey());

        buttonFor("https://a.example").performClick();
        assertEquals("https://a.example", loaded());
        assertEquals("https://a.example", activeKey());
    }

    @Test
    public void longPressingAServerForgetsIt() {
        seedServers("https://b.example");
        rebuild();
        assertEquals(2, serverButtons());

        buttonFor("https://a.example").performLongClick();

        assertEquals(1, serverButtons());
        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
        assertTrue(statusText().contains("已删除"));
    }

    @Test
    public void pairingTheSameMachineAgainMergesInsteadOfDuplicating() {
        // 第一次用隧道配对（二维码里同时有局域网和隧道两条）
        pair("{\"code\":\"c1\",\"urls\":[\"http://192.168.1.2:49374\",\"https://tunnel.example\"]}");
        client.onPageFinished(web, "https://tunnel.example/");
        assertEquals(1, MainActivity.decodeServers(prefs.getString("servers", "")).size());

        // 之后又用局域网那条链接配了一次同一台机器
        pair("http://192.168.1.2:49374/auth/connect/code2");
        client.onPageFinished(web, "http://192.168.1.2:49374/");

        assertEquals("不该变成两条", 1, MainActivity.decodeServers(prefs.getString("servers", "")).size());
        assertEquals(2, MainActivity.decodeServers(prefs.getString("servers", "")).get(0).origins.size());
    }

    @Test
    public void launcherShortcutOpensTheServerList() {
        seedServers("https://a.example");
        Intent intent = new Intent(Intent.ACTION_VIEW).putExtra("screen", "servers");
        activity = Robolectric.buildActivity(MainActivity.class, intent).setup().get();
        web = (WebView) find(myRoot(), WebView.class);

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertNull("不该顺手连上去", loaded());
        assertEquals(2, serverButtons());
    }

    @Test
    public void theShortcutOnARunningAppAlsoOpensTheList() {
        seedServers("https://a.example");
        rebuild();
        assertEquals("https://a.example", loaded());

        activity.onNewIntent(new Intent(Intent.ACTION_VIEW).putExtra("screen", "servers"));

        assertEquals(View.VISIBLE, setupBox().getVisibility());
        assertEquals("web 还在原地，切回来不用重新加载", "https://a.example", loaded());
    }

    @Test
    public void forgettingTheActiveServerFallsBackToTheRemainingOne() {
        seedServers("https://a.example");
        rebuild();

        buttonFor("https://a.example").performLongClick();

        assertEquals(1, serverButtons());
        assertEquals("当前落到剩下那台", "https://b.example", activeKey());
        assertEquals("留在管理页，不自动加载（见 theDeleteOfTheActiveServerStaysInTheManagerAndCanBeUndone）",
                View.VISIBLE, setupBox().getVisibility());
    }

    /** 连接失败那次抛出的 5xx：WebViewClient 会把主文档的 HTTP 错误回调进来。 */
    @Test
    public void aServerThatAnswersWith5xxMovesToTheNextAddress() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://halfway.example\",\"https://good.example\"]}");

        client.onReceivedHttpError(web, request(loaded()),
                new WebResourceResponse("text/html", "utf-8", 503, "Unavailable", null, null));

        assertEquals("https://good.example/auth/connect/c1", loaded());
    }

    @Test
    public void a4xxIsLeftToThePageSoTheExpiredMessageWins() {
        pair("{\"code\":\"c1\",\"urls\":[\"https://a.example\"]}");

        activity.onHttpError(loaded(), 404);

        assertEquals("码的问题交给页面提示，不该换地址", "https://a.example/auth/connect/c1", loaded());
    }

    // ---------------------------------------------------------------- 上次的路由

    @Test
    public void coldStartGoesBackToTheLastRoute() {
        seedRoute("https://a.example/session/ses_9");
        rebuild();

        assertEquals("https://a.example/session/ses_9", loaded());
    }

    @Test
    public void aRouteFromAnAddressThatIsNoLongerThePreferredOneIsIgnored() {
        // 上次走的是 old.example，现在首选是 a.example：宁可回根页面，也不要先撞一次超时
        seedRoute("https://old.example/session/ses_9");
        rebuild();

        assertEquals("https://a.example", loaded());
    }

    @Test
    public void leavingTheAppRemembersTheRouteButOnlyForThisServer() {
        pair("https://a.example/auth/connect/code1");
        client.onPageFinished(web, "https://a.example/");

        activity.rememberRoute("https://a.example/session/ses_7");

        assertEquals("https://a.example/session/ses_7", storedRoute());
        activity.rememberRoute("https://evil.example/phish");
        activity.rememberRoute("https://a.example/auth/connect/other");
        assertEquals("外站和配对页都不许覆盖", "https://a.example/session/ses_7", storedRoute());
    }

    // ---------------------------------------------------------------- 出壳策略

    @Test
    public void untrustedLinksLeaveTheShellAndTrustedOnesStay() {
        pair("https://a.example/auth/connect/code1");
        client.onPageFinished(web, "https://a.example/");

        assertTrue(client.shouldOverrideUrlLoading(web, request("https://evil.example/phish")));
        Intent external = shadowOf(activity).getNextStartedActivity();
        assertNotNull(external);
        assertEquals(Intent.ACTION_VIEW, external.getAction());
        assertEquals("https://evil.example/phish", String.valueOf(external.getData()));

        assertEquals(false, client.shouldOverrideUrlLoading(web, request("https://a.example/session/ses_1")));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void anotherStoredServersAddressLeavesTheShellTooBecauseSwitchingIsExplicit() {
        seedServers("https://a.example");
        rebuild();

        assertTrue(client.shouldOverrideUrlLoading(web, request("https://b.example/session/ses_1")));
        assertNotNull(shadowOf(activity).getNextStartedActivity());
    }

    // ---------------------------------------------------------------- 管理页 / 失败 / 刷新的收尾

    @Test
    public void deletingAServerInTheManagerDoesNotKillTheLivePageBehindIt() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded()); // 页面活着
        activity.openManager(null);

        buttonFor("https://b.example").performLongClick(); // 删非当前那台（走 say）

        buttonFor("https://a.example").performClick(); // 回当前那台：不重新加载
        assertEquals("https://a.example", loaded());

        activity.onPause();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
        activity.onResume();
        assertEquals("页面还是活的：锁屏回来仍然要重新订阅事件流",
                "window.dispatchEvent(new Event('online'))", shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test
    public void abortingAPairingDoesNotKillTheLivePageAndKeepsTheLink() {
        seedServers("https://a.example");
        rebuild();
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);
        client.onPageFinished(web, route);
        activity.openManager(null);
        EditText field = (EditText) find(setupBox(), EditText.class);
        field.setText("https://example.com/pair/link");

        activity.abortPairing();

        assertTrue(statusText(), statusText().contains("已取消"));
        assertEquals("粘的链接留着：用户可能只是想改一下再试", "https://example.com/pair/link",
                field.getText().toString());

        activity.resumeCurrent();
        assertEquals("页面还在，直接回来", View.VISIBLE, web.getVisibility());
        assertEquals(route, loaded());
        activity.resyncPage("测试");
        assertEquals("没被判死：webReady 还在，resync 照发",
                "window.dispatchEvent(new Event('online'))", shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test
    public void aBackgroundFailureDoesNotYankThePageBackWhileInTheManager() {
        seedServers("https://a.example");
        rebuild();
        activity.openManager(null); // 首响应还没来就进了管理页
        EditText field = (EditText) find(setupBox(), EditText.class);
        field.setText("https://example.com/typing"); // 用户正在粘另一条链接
        // 先假装状态行是中性消息：失败必须把它改成红色（不然和"已删除 …"那种提示分不出来）
        ((TextView) activity.findViewById(R.id.setup_status))
                .setTextColor(activity.getColor(R.color.shell_text_muted));

        activity.onLoadFailed("https://a.example", "net::ERR_CONNECTION_REFUSED");

        assertEquals("后台那次加载的结论只写状态行", View.VISIBLE, setupBox().getVisibility());
        assertEquals(View.GONE, web.getVisibility());
        assertTrue(statusText(), statusText().contains("连不上这台电脑"));
        assertEquals("失败就该标红，不能是 say() 的中性灰",
                activity.getColor(R.color.shell_status), statusColor());
        assertEquals("不能替用户换页/重新加载", "https://a.example", loaded());
        assertEquals("也不能把他打了一半的链接清掉", "https://example.com/typing",
                field.getText().toString());
    }

    @Test
    public void aBackgroundFailureStillMarksThePageDeadSoTappingTheCurrentServerReconnects() {
        seedServers("https://a.example");
        rebuild();
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);
        client.onPageFinished(web, route);
        activity.openManager(null);

        // 页面本来是活的：失败不能走"原地重试"（人在管理页，重试醒来只会空转），得直接判死并写状态
        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED");
        assertTrue("后台失败也得写状态行：" + statusText(), statusText().contains("连不上这台电脑"));

        buttonFor("https://a.example").performClick(); // 点当前那台

        assertEquals("得重连刚才那个页面，不能把 Chromium 的错误页翻出来", route, loaded());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.load_bar).getVisibility());
        assertEquals(View.GONE, setupBox().getVisibility());
    }

    @Test
    public void tappingTheCurrentServerAfterAFailureRetriesInsteadOfShowingTheErrorPage() {
        seedServers("https://a.example");
        rebuild();
        activity.onLoadFailed("https://a.example", "net::ERR_CONNECTION_REFUSED"); // 单候选：判死
        assertEquals(View.VISIBLE, setupBox().getVisibility());

        buttonFor("https://a.example").performClick();

        assertEquals("应该重新连，而不是把 Chromium 的错误页翻出来", "https://a.example", loaded());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.load_bar).getVisibility());
        assertEquals(View.GONE, setupBox().getVisibility());
    }

    @Test
    public void aRefreshFailureStillGetsTheInPlaceRetryAndKeepsTheDeepLink() {
        seedServers("https://a.example");
        rebuild();
        String route = "https://a.example/server/k/session/ses_1";
        web.loadUrl(route);
        client.onPageFinished(web, route);

        activity.reloadPage();
        activity.onLoadFailed(route, "net::ERR_CONNECTION_REFUSED");

        assertTrue("刷新失败也要说在重试", ShadowToast.getTextOfLatestToast().contains("正在重试"));
        assertEquals("不能把人踢回管理页", View.GONE, setupBox().getVisibility());

        // 影子只记账、不触发回调：先抹掉"最后一次加载"，800ms 后还看到这条深链才算真重试了
        web.loadUrl("about:blank");
        assertEquals("about:blank", loaded());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800));
        assertEquals("重试真的重新发了一次加载，不是只弹个 toast", route, loaded());
    }

    @Test
    public void a5xxOnThePairingLandingPageIsNotRecordedAsSuccess() {
        pair("https://a.example/auth/connect/c1");

        client.onReceivedHttpError(web, request("https://a.example/"),
                new WebResourceResponse("text/html", "utf-8", 503, "Unavailable", null, null));
        client.onPageFinished(web, "https://a.example/"); // Chromium 的错误页也会 finish

        assertEquals("配对没落地，不许写进列表", "", prefs.getString("servers", ""));
        assertTrue(statusText(), statusText().contains("503"));
    }

    @Test
    public void pastingThePrintedLinkWhileTheSameCodeIsRedeemingDoesNotSpendItTwice() {
        List<Runnable> pending = new ArrayList<>();
        beforeCreate = () -> activity.redemptionExecutor = pending::add;
        rebuild();

        pair("https://a.example/auth/connect/c1"); // 兑换被挂住，码还在飞
        assertEquals(1, pending.size());

        EditText field = (EditText) find(setupBox(), EditText.class);
        field.setText("http://192.168.1.2:4096/auth/connect/c1"); // 同一条码，另一条地址形态
        buttonTitled("连接").performClick();

        assertEquals("同一个码还在兑换中：不许再发一次把它花掉", 1, pending.size());
    }

    @Test
    public void aResyncMissedWhileInTheManagerIsReplayedOnReturn() {
        seedServers("https://a.example");
        rebuild();
        client.onPageFinished(web, loaded());
        activity.openManager(null);

        activity.resyncPage("网络变化");
        assertNull("人在管理页：不许往藏起来的页面里塞事件", shadowOf(web).getLastEvaluatedJavascript());

        activity.resumeCurrent();
        assertEquals("回到页面要补上那一次", "window.dispatchEvent(new Event('online'))",
                shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test
    public void anExternalIframeDoesNotOpenTheSystemBrowser() {
        seedServers("https://a.example");
        rebuild();

        assertFalse("子框架里的外站不该把整页踢到浏览器",
                client.shouldOverrideUrlLoading(web, request("https://evil.example/embed", false)));
        assertNull("子框架不接管：连系统浏览器都不该起", shadowOf(activity).getNextStartedActivity());

        assertTrue("主框架照旧丢出去",
                client.shouldOverrideUrlLoading(web, request("https://evil.example/phish")));
        Intent external = shadowOf(activity).getNextStartedActivity();
        assertNotNull("主框架才交给系统浏览器", external);
        assertEquals("https://evil.example/phish", String.valueOf(external.getData()));
    }

    @Test
    public void aBlobDownloadSaysTheShellCannotSaveFiles() {
        seedServers("https://a.example");
        rebuild();

        assertTrue(client.shouldOverrideUrlLoading(web, request("blob:https://a.example/xyz")));

        assertTrue(ShadowToast.getTextOfLatestToast().contains("不能保存文件"));
    }

    @Test
    public void namesAreSanitizedAndCapped() {
        assertEquals("控制字符变空格再压成一个", "a b", MainActivity.sanitizeName("a\nb"));
        assertFalse("双向控制符能把整行显示成反的", MainActivity.sanitizeName("x\u202Ey").contains("\u202E"));
        assertFalse("阿拉伯字母标记也是不可见的方向控制",
                MainActivity.sanitizeName("x\u061Cy").contains("\u061C"));
        assertEquals("零宽连接符/BOM 直接去掉", "xy", MainActivity.sanitizeName("x\uFEFF\u2060y"));
        assertEquals("C1 控制字符也当空白（U+0090 不是空白字符，只能靠显式码位洗）",
                "a b", MainActivity.sanitizeName("a\u0090b"));
        assertEquals(24, MainActivity.sanitizeName("家".repeat(100)).length());
        String emoji = MainActivity.sanitizeName("😀".repeat(30));
        assertEquals("按码位截，24 个 emoji 是 48 个 UTF-16 单元", 24,
                emoji.codePointCount(0, emoji.length()));
        assertFalse("不能把 emoji 劈成半个（末尾落单的高位代理）", emoji.endsWith("\uD83D"));
        assertEquals("全空白等于没起名", "", MainActivity.sanitizeName("   "));
    }

    // ---------------------------------------------------------------- 工具

    @Test
    public void theConnectScreenKeepsItsPaddingUnderSystemBars() {
        // insets 里补 systemBars 时别把 buildSetup 里的页边距吃成 0（Android 11+ 连接页会贴边）
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density); // = dimens 里的 shell_page_padding
        WindowInsets bars = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(10, 100, 10, 60))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 0))
                .build();

        myRoot().dispatchApplyWindowInsets(bars);

        assertEquals("左右留白不许被 insets 吃掉", pad, setupBox().getPaddingLeft());
        assertEquals(pad, setupBox().getPaddingRight());
        assertEquals(100 + pad, setupBox().getPaddingTop());
        assertEquals("底部的导航栏让给 root 统一处理，连接页只留自己的页边距", pad, setupBox().getPaddingBottom());
        assertEquals("导航栏高度要让给 WebView：不然 composer 会压在系统手势条下面", 60,
                myRoot().getPaddingBottom());
        // 加载进度线要落在状态栏下沿：画在 y=0 会跟系统时间/刘海糊在一起
        assertEquals(100, ((android.widget.FrameLayout.LayoutParams)
                activity.findViewById(R.id.load_bar).getLayoutParams()).topMargin);
    }

    @Test
    public void theImeAndTheNavigationBarAreNotAddedTwice() {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        WindowInsets typing = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(10, 100, 10, 60))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 500))
                .build();

        myRoot().dispatchApplyWindowInsets(typing);

        assertEquals("键盘比导航栏高：取键盘高度，不是两者相加", 500, myRoot().getPaddingBottom());
        assertEquals("连接页不再自己补底部，否则会和 root 叠成两层", pad, setupBox().getPaddingBottom());
    }

    private void pair(String payload) {
        ((EditText) find(setupBox(), EditText.class)).setText(payload);
        buttonTitled("连接").performClick();
        // 兑换在后台线程上跑（测试里换成 inline），完成回调经 main handler 回来
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void seedServers(String activeKey) {
        MainActivity.Server a = new MainActivity.Server("https://a.example", Collections.singletonList("https://a.example"));
        MainActivity.Server b = new MainActivity.Server("https://b.example", Collections.singletonList("https://b.example"));
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Arrays.asList(a, b)))
                .putString("active", activeKey)
                .commit();
    }

    /** 存一台 a.example，并带上"上次停在哪个路由"。 */
    private void seedRoute(String route) {
        MainActivity.Server a = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"), route);
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(a)))
                .putString("active", "https://a.example")
                .commit();
    }

    /** 一台有两个候选地址的服务器，上次停在第二条（局域网）上。 */
    private void seedRouteOnSecondaryCandidate(String route) {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"), route);
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();
    }

    /** 一台只有一个候选地址的服务器（默认的隧道形态），上次停在某个路由上。 */
    private void seedRouteOnPreferredCandidate(String route) {
        MainActivity.Server server = new MainActivity.Server("https://tunnel.example",
                Collections.singletonList("https://tunnel.example"), route);
        prefs.edit()
                .putString("servers", MainActivity.encodeServers(Collections.singletonList(server)))
                .putString("active", "https://tunnel.example")
                .commit();
    }

    private static WebChromeClient.FileChooserParams chooserParams() {
        return new WebChromeClient.FileChooserParams() {
            @Override
            public Intent createIntent() {
                return new Intent(Intent.ACTION_GET_CONTENT).setType("*/*");
            }

            @Override
            public String[] getAcceptTypes() {
                return new String[]{"*/*"};
            }

            @Override
            public boolean isCaptureEnabled() {
                return false;
            }

            @Override
            public CharSequence getTitle() {
                return null;
            }

            @Override
            public String getFilenameHint() {
                return null;
            }

            @Override
            public int getMode() {
                return MODE_OPEN;
            }
        };
    }

    private String storedRoute() {
        return MainActivity.decodeServers(prefs.getString("servers", "")).get(0).route;
    }


    /**
     * 先建实例、装好桩，再跑生命周期（onCreate）。顺序不能反：冷启动那次加载会顺手把候选地址探测排进
     * looper，桩要是晚一步，跑起来的就不是桩、而是真网络。
     */
    private void createActivity() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        activity = controller.get();
        stubRedeem();
        if (beforeCreate != null) {
            beforeCreate.run();
            beforeCreate = null;
        }
        controller.setup();
        web = (WebView) find(myRoot(), WebView.class);
        client = shadowOf(web).getWebViewClient();
    }

    private void rebuild() {
        createActivity();
    }

    /** 模拟 fontScale / 旋转：先存 instance state，再按它重建 Activity。 */
    private void rebuildAfterSave() {
        Bundle state = new Bundle();
        activity.onSaveInstanceState(state);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class);
        activity = controller.get();
        stubRedeem();
        controller.setup(state);
        web = (WebView) find(myRoot(), WebView.class);
        client = shadowOf(web).getWebViewClient();
    }

    private String loaded() {
        return shadowOf(web).getLastLoadedUrl();
    }

    private String activeKey() {
        String key = prefs.getString("active", "");
        return key.isEmpty() ? null : key;
    }

    private ViewGroup myRoot() {
        return (ViewGroup) ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0);
    }

    private LinearLayout setupBox() {
        return (LinearLayout) myRoot().getChildAt(1);
    }

    /** 输入框下面那行随内容变的说明（TextInputLayout 的 helper）。 */
    private CharSequence helperText() {
        TextInputLayout field = (TextInputLayout) find(setupBox(), TextInputLayout.class);
        return field == null || field.getHelperText() == null ? "" : field.getHelperText();
    }

    /** 配对进行中的那条进度条。 */
    private View progressBar() {
        return find(setupBox(), ProgressBar.class);
    }

    /** 删除后的撤销条。 */
    private View undoRow() {
        return activity.findViewById(R.id.setup_undo);
    }

    /** 输入框上的错误（链接本身的问题就近显示在这里）。 */
    private CharSequence fieldError() {
        TextInputLayout field = (TextInputLayout) activity.findViewById(R.id.setup_field);
        return field == null || field.getError() == null ? "" : field.getError();
    }

    /** 一个 view 子树里所有 TextView 的文字（自绘面板这类内容按文字断言）。 */
    private String textOf(View root) {
        StringBuilder all = new StringBuilder();
        for (TextView view : all(root, TextView.class)) all.append(view.getText()).append(" | ");
        return all.toString();
    }

    /** 表头上那种"文字动作"（「＋ 添加」/「收起」），不是 Button。 */
    private TextView actionTitled(String title) {
        for (TextView view : all(setupBox(), TextView.class)) {
            if (title.contentEquals(view.getText())) return view;
        }
        throw new AssertionError("没有文字动作：" + title);
    }

    /** 拖球：按下 → 拖走 → 松手（最后一帧离抬手 0px，正好卡在旧的"拖完被当成点击"那个坑上）。 */
    private void dragBall(View ball) {
        long t = SystemClock.uptimeMillis();
        ball.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 100, 100, 0));
        ball.dispatchTouchEvent(MotionEvent.obtain(t, t + 20, MotionEvent.ACTION_MOVE, 40, 100, 0));
        ball.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_MOVE, 0, 100, 0));
        ball.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 0, 100, 0));
    }

    /** 行尾的 ⋮（更多操作）：从这一行（tag=key 的名字按钮）往上找，同一张卡片里的那个无 tag 按钮。 */
    private Button overflowFor(String key) {
        ViewGroup row = (ViewGroup) buttonFor(key).getParent().getParent(); // column → line(卡片)
        for (Button button : all(row, Button.class)) {
            if (button.getTag() == null) return button;
        }
        throw new AssertionError("这一行没有 ⋮ 菜单：" + key);
    }

    /** 服务器行第二行（完整地址 + 候选条数）的原文。 */
    private String addressLine(String key) {
        for (TextView view : all(setupBox(), TextView.class)) {
            if ((MainActivity.ADDRESS_TAG + key).equals(view.getTag())) return view.getText().toString();
        }
        throw new AssertionError("没有地址行：" + key);
    }

    /** 连接页里稳定可取的那几个 view 用 id 找（不靠"第几个孩子""第几个 TextView"）。 */
    private String statusText() {
        TextView status = activity.findViewById(R.id.setup_status);
        return status == null ? "" : status.getText().toString();
    }

    private int statusColor() {
        TextView status = activity.findViewById(R.id.setup_status);
        return status == null ? 0 : status.getCurrentTextColor();
    }

    private Button buttonTitled(String title) {
        for (Button button : all(setupBox(), Button.class)) {
            if (title.contentEquals(button.getText())) return button;
        }
        throw new AssertionError("没有标题是 " + title + " 的按钮");
    }

    private Button buttonFor(String key) {
        for (Button button : all(setupBox(), Button.class)) {
            if (key.equals(button.getTag())) return button;
        }
        throw new AssertionError("列表里没有 " + key);
    }

    private int serverButtons() {
        int count = 0;
        for (Button button : all(setupBox(), Button.class)) {
            if (button.getTag() != null) count++;
        }
        return count;
    }

    private static <T extends View> java.util.List<T> all(View root, Class<T> type) {
        java.util.List<T> found = new java.util.ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(all(group.getChildAt(i), type));
        }
        return found;
    }

    private static <T extends View> View find(View root, Class<T> type) {
        java.util.List<T> found = all(root, type);
        return found.isEmpty() ? null : found.get(0);
    }

    private static WebResourceRequest request(String url) {
        return request(url, true);
    }

    private static WebResourceRequest request(String url, boolean mainFrame) {
        return new WebResourceRequest() {
            @Override
            public Uri getUrl() {
                return Uri.parse(url);
            }

            @Override
            public boolean isForMainFrame() {
                return mainFrame;
            }

            @Override
            public boolean isRedirect() {
                return false;
            }

            @Override
            public boolean hasGesture() {
                return true;
            }

            @Override
            public String getMethod() {
                return "GET";
            }

            @Override
            public Map<String, String> getRequestHeaders() {
                return Collections.emptyMap();
            }
        };
    }
}
