package io.github.chyax98.opencodeclient;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.webkit.Profile;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * OpenCode 的 Android 壳：WebView 跑官方的 Web UI，配对用 opencode pair 输出的链接或二维码。
 *
 * 配对完全复用服务端的浏览器流程：一次性码 /auth/connect/&lt;code&gt; 种下 session cookie（30 天）后 302 到 /，
 * 所以这里只要记住配对用的 origin 就够了，不需要自己调 API、也不需要存密码。
 *
 * opencode pair 的实际能力（对逻辑有影响的部分）：
 * - 码是 randomBytes(16).toString("base64url")，5 分钟过期、一次性（packages/server/src/pairing.ts）
 * - 二维码内容是 {"code","urls"}，urls 已滤掉 loopback；打印出来的链接则包含 127.0.0.1 和隧道两条
 * - urls 可能有多条（局域网 http + 隧道 https），这里优先 https，连不上再退到下一条
 *
 * 只信任配对过的 origin：其余 http(s) 链接、mailto/tel、window.open / target=_blank 全部丢给系统浏览器。
 * 官方 Web UI 里的 OAuth（"Open browser"）、文档链接、markdown 里的链接都靠这条才能点得动。
 */
public class MainActivity extends Activity {

    private static final String TAG = "OpenCodeClient";
    private static final String PREFS = "opencode";
    private static final String KEY_SERVERS = "servers"; // JSON: [{"key":"https://a","origins":["https://a","http://1.2.3.4"]}]
    private static final String KEY_ACTIVE = "active";   // 当前服务器 = Server.key
    private static final String CONNECT_PATH = "/auth/connect/";
    private static final int PERMISSION_REQUEST_CAMERA = 0x5f4;
    private static final long ROUTE_TIMEOUT_MS = 4_000; // 试"上次那个页面"时用短超时，连不上就回首页
    private static final long UNDO_MS = 5_000; // 删除后的撤销窗口
    private static final long BACK_ARM_MS = 2_000; // 返回键"再按一次才进管理页"的有效期
    private static final String STATE_MANAGER = "manager"; // 重建（fontScale/旋转）时"人是不是在管理页"
    static final String ADDRESS_TAG = "addr:"; // 服务器行第二行（完整地址）的 tag，给测试一个稳定的拿法
    private static final String EXTRA_SCREEN = "screen"; // 启动器快捷方式 → 直接进服务器列表
    private static final int FILE_CHOOSER_REQUEST = 0x5f3;
    private static final long FIRST_RESPONSE_TIMEOUT_MS = 20_000;
    // 后台超过这么久再回来，就让页面重新订阅一次事件流：锁屏/切出去时那条长连接多半已经半开，
    // 页面的读操作又不会报错，数据就会停在离开前那一帧。
    private static final long RESUME_RESYNC_AFTER_MS = 10_000;
    private static final long NETWORK_SETTLE_MS = 1_000; // 刚换网络时第一个包常还是坏的，等一下再让页面重连
    private static final long PAGE_RETRY_DELAY_MS = 800;
    private static final int NAME_MAX = 24; // 名字要进列表行 / 撤销条 / 读屏：超过这个长度就是在撑坏布局

    private SharedPreferences prefs;
    private WebView web;
    private FrameLayout root; // 唯一的容器：WebView 按 0 号位插进来（setup/球/进度条都在它后面）
    // 已经加载过、被切走的那台服务器。切回来直接显示，不再走一次 SPA 冷启动。
    // 上限 1：最多同时留两张 WebView（当前 + 一张停着的），再多的按最近最少使用丢掉。
    private static final int WARM_LIMIT = 1;
    private final Map<String, WebView> warm = new LinkedHashMap<>();
    private final Map<String, Long> warmSince = new LinkedHashMap<>(); // 停着的时刻（elapsedRealtime）
    private final Map<String, Long> preconnectedAt = new HashMap<>(); // 最近一次给这条 origin 预热连接的时刻
    // Profile.preconnect 的连接大约活 30 秒：20 秒内重复调用没有意义，别每次页面 finish 都发一遍。
    private static final long PRECONNECT_REFRESH_MS = 20_000;
    private final Set<WebView> renderDead = Collections.newSetFromMap(new IdentityHashMap<>()); // 渲染进程没了的视图：只能 destroy，不能再调方法
    // 停着超过这么久，切回来先让页面重订一次事件流（短时间的来回切就不必，SSE 还活着）。
    private static final long WARM_RESYNC_AFTER_MS = 10_000;
    private long switchAt; // 这次切台的开始时刻；0 = 没在计时
    private long switchVisibleAt; // 冷切第一次画出内容（onPageCommitVisible）的时刻；0 = 还没画出来
    private boolean switchWasWarm; // 这次是热切还是冷切（只给日志用）
    private LinearLayout setup;
    private LinearLayout serverList;
    private EditText input;
    private TextView status;
    private TextView intro;
    private TextView pairHint; // "用哪条命令 / 码只能用一次 / 127.0.0.1 别粘"：配对表单展开时才有
    private TextView copyPairCommand; // 一键复制 opencode pair --remote
    private ImageView logo; // 顶栏那个官方标识：管理态收小，连接态是大图
    private TextView pageTitle; // "OpenCode"：管理态降到 16sp
    private MaterialButton connectButton; // 配对表单：连接
    private MaterialButton scannerButton; // 配对表单：扫描二维码
    private boolean formExpanded; // 有服务器时配对表单默认收起（日常进来是切台，不是配对） // 首屏那段"怎么配对"的说明：有服务器以后就收起来（日常进来不该先滚过 onboarding）
    private ProgressBar progress; // 配对进行中的那条进度条（"正在配对…" 时亮）
    private ProgressBar loadBar; // 页面加载进度：贴在屏幕最上面的 3dp 细线（Web UI 里唯一能看见"正在加载"的地方）
    private int loadPercent; // 0..99；面板里那句"正在加载 42%"用它
    private int loadBarTop; // 状态栏高度：进度线画在它下面，别糊在系统时间和刘海里
    private TextView refreshState; // 悬浮球面板里那行"正在加载 42% · 点这里重新加载"（面板开着时要跟着进度变）
    private TextInputLayout inputField; // 输入框外壳：链接本身的问题就近标在它上面
    private MaterialButton cancelButton; // 配对进行中的「取消」（平时藏着）
    int historyClears; // 换服务器时清 WebView 历史的次数（只在测试里看：Robolectric 不模拟 WebView 历史）
    private AlertDialog preview; // 悬浮球那个「切服务器」面板
    private AlertDialog actions; // 行尾 ⋮ 的动作面板 // 悬浮球那个「切服务器」面板（自绘列表，不用系统单选列表）
    private LinearLayout undoBar; // "已移除 X（可撤销）"，不用 Material Snackbar：Robolectric 里断言不到
    private TextView undoText;
    private Server undoServer; // 等着被撤销放回去的那台
    private int undoIndex; // 它原来在列表里的位置
    private boolean undoWasActive; // 删掉的正是当前那台（撤销时要连"当前"一起还回去）
    private final Runnable undoExpiry = () -> dismissUndo();
    private boolean backOnceArmed; // 返回键"再按一次才进管理页"（2 秒内有效）
    private ServerBall ball; // 全屏 Web UI 上的悬浮球（回管理页的唯一可见入口）
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long backgroundedAt; // 进后台的时刻（elapsedRealtime）；0 = 现在不在后台
    private ConnectivityManager.NetworkCallback networkCallback; // 换网络后让页面重连一次
    private boolean networkNudgePending; // 去抖：系统一次切换会连着报好几次
    private final Runnable networkNudge = () -> {
        networkNudgePending = false;
        resyncPage("网络变化");
    };
    private int pageRetries; // 用得好好的一次加载失败：只原地重试一次，别把人踢回管理页
    private boolean pageRetryInFlight; // 这次加载是"原地重试"：failedUrl 要等它真开始导航才解开
    private boolean pageDead; // 最后一次尝试把候选链走完了（页面已经判死）：点当前那台要重连，不是把错误页翻出来
    private boolean pageWasLive; // 这次加载是用户主动刷新一张活页面：失败也该享受"原地重试一次"
    private boolean managerOpen; // 人在管理页：后台的加载结果不许把页面抢回来
    private boolean resyncPending; // 管理页期间错过的"重新订阅"：回到页面时补一次
    private Runnable pageRetry; // 排队中的那次重试（销毁/回管理页时要撤掉）

    private List<Server> servers = new ArrayList<>();
    private Server active; // null → 显示连接页
    // 正在配对/切换：非空时优先用这些地址，配对成功才写进 servers（失败不会留下死条目）
    private List<String> attemptOrigins = Collections.emptyList();
    private String attemptKey = "";
    private String pairingCode = "";
    private int candidate;
    private Runnable watchdog;
    private boolean attemptFinished; // 这次尝试已经有结论（失败/成功）了，晚到的 onPageFinished 不许再改状态
    private String failedUrl; // 这次尝试里已经失败的 URL：Chromium 会给失败的主框架再补一个 onPageFinished
    private boolean routeAttempt; // 正在试"上次那个页面"，失败就直接回首页，不占用候选链
    private boolean renderGone; // 渲染进程没了：这个 WebView 只能丢掉，不能再调它任何方法
    private boolean webReady; // WebView 里已经有一张能用的页面（用来区分"正在连"和"正在用"）
    private boolean authHintShown; // 这次会话已经说过"登录被撤销"了：别每个 401 都弹一次
    private PermissionRequest pendingPermission; // 等系统相机权限对话框期间挂起的 WebView 授权请求
    private ValueCallback<Uri[]> fileCallback;
    private String lastPairLink; // 上一次粘进来的配对链接：503 那种"还可以再试"的失败要把原文留在输入框里
    // 配对走 API 兑换（见 Redeem）：拿到 token 前先不加载页面，避免赌 302/HTML 的中间环节。
    // 这两个调用与执行器都做成可替换的：JVM 测试里换成桩，就不用为了状态机去跑真网络（真实现由 RedeemTest 覆盖）。
    Executor redemptionExecutor = Executors.newSingleThreadExecutor();
    Executor probeExecutor = Executors.newSingleThreadExecutor();
    // 在飞的 window.open 探针：它们没挂到视图树上，View.postDelayed 永远不会跑，销毁只能我们自己兜底。
    private final Set<WebView> probes = Collections.newSetFromMap(new IdentityHashMap<>());
    BiFunction<String, String, Redeem.Result> redeemAttempt = Redeem::attempt;
    BiFunction<String, String, Redeem.Verify> redeemVerify = Redeem::tokenWorks;
    // 候选地址探测（B）：JVM 测试里换成桩，就不必为了状态机去开线程/连真地址
    Function<List<String>, Map<String, Probe.Liveness>> probeAll = Probe::probeAll;
    private Set<String> probedDead = Collections.emptySet(); // 这次尝试里已探到"连不上"的候选地址
    private String loadingUrl; // 正在等第一个响应的那个 URL（探测据此判断"要不要提前换代"）
    private boolean redeemInFlight; // 一次兑换还在飞：同一条码就别再发第二次（码是一次性的，第二次必然 401）
    private int redeemGeneration; // 每次"开始一次尝试/切服务器/取消"都自增，晚到的兑换结果凭它丢弃
    private String pendingToken; // 兑换到、还没随服务器一起落盘的 token
    private String pendingTokenOrigin; // 它是在哪条 origin 上兑换出来的（token 只用在这一条上）
    // 渲染进程连续崩（static：跨 Activity 实例计数）。可能是这个页面本身把 renderer 打崩了，
    // 连续几次就直接停在连接页，别陷入"重建 → 再崩 → 再重建"的闪屏。
    private static int renderGoneStreak;
    private static long lastRenderGoneAt;
    static boolean startOnSetup; // 静态：崩溃循环用 recreate() 重建，标记要跨 Activity 实例传

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        servers = decodeServers(prefs.getString(KEY_SERVERS, ""));
        assignMissingSlots(servers); // 老数据没有自动编号：按顺序补上，列表里就不拿长地址当名字
        active = findServer(servers, prefs.getString(KEY_ACTIVE, ""));
        if (active == null && !servers.isEmpty()) active = servers.get(0);
        // 启动器快捷方式（screen=servers）直接进服务器列表；否则自动连上次那台。
        boolean showList = wantsServerList() || active == null
                || (savedInstanceState != null && savedInstanceState.getBoolean(STATE_MANAGER, false));
        boolean crashLoop = startOnSetup; // 上次是"渲染进程反复崩"退下来的
        startOnSetup = false;
        // 独立的 IME inset 是 API 30 才有的。更早的版本只有窗口真被键盘 resize 时
        // getSystemWindowInsetBottom() 才反映键盘高度，而 manifest 里是 adjustNothing（永不 resize）
        // → 旧系统上键盘会直接盖住输入框。那时改回 adjustResize 让窗口自己缩（下面就不再重复扣了）。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        root = new FrameLayout(this);
        web = buildWebView();
        setup = buildSetup();
        // 第一帧就摆对：有服务器就直接显示 WebView，别先闪一下连接页。
        web.setVisibility(showList ? View.GONE : View.VISIBLE);
        setup.setVisibility(showList ? View.VISIBLE : View.GONE);
        root.addView(web, fill());
        root.addView(setup, fill());
        // Android 15 起 targetSdk 35 强制 edge-to-edge，adjustResize 不再缩窗口，键盘会直接盖住输入框。
        // 统一自己处理：把键盘/导航栏里更高的那个作为 root 的底部 padding（WebView 因此变矮 → 页面视口跟着缩）。
        // 状态栏避让仍然交给 Web UI 自己的 safe-area（实测 env(safe-area-inset-top) 有值）；
        // 但 bottom 实测是 0（Android 的 WebView 不暴露手势条/导航栏），所以底部只能壳自己补，
        // 否则 composer 会被压在系统手势条下面。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
            int keyboard = modern
                    ? insets.getInsets(WindowInsets.Type.ime()).bottom
                    : insets.getSystemWindowInsetBottom();
            int systemBottom = modern
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            v.setPadding(0, 0, 0, Math.max(keyboard, systemBottom));
            // 加载进度线放在状态栏下沿：画在 y=0 会跟系统时间/刘海/圆角糊在一起。
            loadBarTop = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? insets.getInsets(WindowInsets.Type.systemBars()).top
                    : insets.getSystemWindowInsetTop();
            applyLoadBarTop();
            // 连接页是原生 view：状态栏的留白它自己补，底部已经由 root 统一让出来了。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && setup != null) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                int pad = getResources().getDimensionPixelSize(R.dimen.shell_page_padding);
                setup.setPadding(pad, bars.top + pad, pad, pad);
            }
            return insets;
        });
        setContentView(root);

        // 悬浮球：root 的第三个孩子（WebView、setup 之后），只有"正在用 Web UI"时才显示。
        ball = new ServerBall(this, prefs, this::showServerPreview);
        root.addView(ball, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        // 旋转/分屏/折叠展开时 Activity 不重建（configChanges），球的像素位置要按新尺寸重新摆，
        // 否则它会停在屏幕外（它是 Web UI 里唯一的管理入口）。
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (ball != null && right - left > 0 && bottom - top > 0) {
                ball.restorePosition(right - left, bottom - top);
            }
        });

        // 页面加载进度：一条 3dp 的细线贴在屏幕最上面。Web UI 自己的骨架屏只在页面内部，
        // 壳发起的加载（冷启动、换服务器、刷新、原地重试）在此之前完全看不出来。
        loadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadBar.setId(R.id.load_bar);
        loadBar.setMax(100);
        loadBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.shell_accent)));
        loadBar.setProgressBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
        loadBar.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        loadBar.setVisibility(View.GONE);
        root.addView(loadBar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));
        applyLoadBarTop();

        renderServers();
        renderBall();
        registerNetworkNudges();
        if (showList) {
            showSetup(null);
        } else if (crashLoop) {
            // 刚才是同一个页面把渲染进程反复打崩：这次别自动回那个页面了。
            showSetup("页面反复崩溃，已回到连接页。");
        } else {
            startAttempt();
        }
    }

    /**
     * 这一页有两个身份，别混着做（混着做就是"日常进来先滚过一遍 onboarding"）：
     * - **一台都没有 → 连接页**：大 logo + 20sp 标题 + 一句怎么配对 + 摊开的配对表单，第一次进来就知道怎么加。
     * - **有服务器 → 管理页**：顶栏收小到 32dp / 16sp，说明收掉，配对表单收成表头上那个「＋ 添加」，
     *   第一眼是服务器列表（日常要的是切台，不是再配一台）。
     */
    private void applyPageLayout() {
        boolean manager = !servers.isEmpty();
        if (logo != null) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) logo.getLayoutParams();
            params.width = params.height = dp(manager ? 32 : 96);
            logo.setLayoutParams(params);
        }
        if (pageTitle != null) pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, manager ? 16 : 20);
        if (intro != null) intro.setVisibility(manager ? View.GONE : View.VISIBLE);
        boolean form = !manager || formExpanded;
        for (View view : new View[]{inputField, connectButton, scannerButton, pairHint, copyPairCommand}) {
            if (view != null) view.setVisibility(form ? View.VISIBLE : View.GONE);
        }
    }

    /** 切回管理页并说一句**中性**的话（已取消 / 已恢复 / 已移除）：红只留给失败。 */
    void say(String message) {
        // 关键：走 openManager，不走 showSetup。删一台服务器/撤销是在管理页做的动作，
        // 不能顺手把背后那张还活着的页面 stopLoading + webReady=false —— 否则回页面后
        // 锁屏再回来不会重新订阅事件流（resyncPage 第一行就返回），正在加载的页面还会被掐成半截。
        openManager(message);
        if (status != null) status.setTextColor(getColor(R.color.shell_text_muted));
    }

    /** 悬浮球只在"有服务器 + 正在看 Web UI"时出现：管理页/配对页里入口就是页面本身。 */
    private void renderBall() {
        if (ball == null || web == null || setup == null) return;
        ball.setVisibility(web.getVisibility() == View.VISIBLE && active != null ? View.VISIBLE : View.GONE);
        // 读屏念到这个球的时候，得能听出"当前在哪台"，不然它只是个没信息的圆
        ball.setContentDescription(active == null ? "管理服务器" : "管理服务器，当前：" + displayName(active));
    }

    /**
     * 开始一次尝试（冷启动、切服务器、删掉当前这台之后）：**加载照旧立刻开始**，
     * 同时并行探一遍候选地址——加载真卡住时用它提前换代，而不是干等 20 秒看门狗。
     */
    private void startAttempt() {
        if (tokenExpiredLocally()) return; // 本地就知道过期了：不加载、也不假装连上了
        if (!hasNetwork()) {
            // 没网时 Chromium 要等一次超时才报错，而且会一条条试候选地址；当场说清楚，
            // 后面那几张地址等有网了再试才有意义。
            Log.i(TAG, "连接失败：" + Failure.OFFLINE);
            showSetup(Failure.OFFLINE);
            return;
        }
        cancelPageRetry();
        pageRetries = 0;
        int route = routeCandidate(active);
        if (route >= 0) {
            // 先试上次那个页面；用短超时，连不上就回首页（failCandidate 里不再占用候选链）。
            routeAttempt = true;
            candidate = route;
            load(active.route, ROUTE_TIMEOUT_MS);
        } else {
            routeAttempt = false;
            candidate = 0;
            loadCandidate();
        }
        // 探测晚一轮再开始：先让加载出去（首帧不受影响），也免得它跟上面这次 load 抢主线程。
        handler.post(this::probeCandidates);
        // 存着的 token 也让服务器自己说一句"还认不认"——见 checkStoredToken 的注释
        handler.post(this::checkStoredToken);
    }

    /**
     * token 自带过期时间（`expires.签名`，30 天，见 {@link Redeem#expired}）：能本地判的就别去请求，
     * 更别把一个注定被拒的 cookie 种进去、然后把 Web UI 自己的登录屏当成"已连接"。
     */
    private boolean tokenExpiredLocally() {
        Server server = active;
        if (server == null || server.token == null) return false;
        if (!Redeem.expired(server.token, System.currentTimeMillis())) return false;
        Log.i(TAG, "存着的 token 已过期：" + server.key);
        clearToken(server);
        showSetup("配对已过期（token 有效期 30 天），请在电脑上重新运行 opencode pair。");
        return true;
    }

    /**
     * 冷启动时问一次服务器"这个 token 还认吗"。壳的"配对成功"判据只看"页面加载完了"，而 Web UI
     * **自己**也有一个登录屏（`shell/routes/routes.tsx` 的 `signedOut()` 判据）：token 被撤销、服务端换过密码、
     * cookie 没种进去时，用户落在那个登录屏上，壳却会显示"已连接"。所以这里用现成的
     * {@link Redeem#tokenWorks}（Basic，只打自己那条 origin）确认一次：
     * 明确拒了（401/403）就清掉 token、回连接页给一条准确的说法；网络不通先不动（别把抖动当成失效）。
     */
    private void checkStoredToken() {
        final Server server = active;
        if (server == null || server.token == null || server.tokenOrigin == null) return;
        final String token = server.token;
        final String origin = baseOf(normalize(server.tokenOrigin));
        if (origin == null) return;
        final int generation = redeemGeneration;
        redemptionExecutor.execute(() -> {
            Redeem.Verify verify = redeemVerify.apply(origin, token);
            if (verify != Redeem.Verify.REJECTED) return;
            postToMain(() -> {
                // 晚到的结果一律不动手：换服务器/删服务器/重新配对/回连接页都会让代际计数往前走
                if (generation != redeemGeneration || isDestroyed() || web == null) return;
                Log.i(TAG, "服务器不认这个 token（" + origin + "）");
                clearToken(server);
                showSetup("这台服务器不认这个会话了（被撤销或换过密码），请在电脑上重新运行 opencode pair。");
            });
        });
    }

    /** 把这条服务器的凭据清掉并落盘（cookie jar 里的那份留着无妨：服务端已经不认它了）。 */
    private void clearToken(Server server) {
        server.token = null;
        server.tokenOrigin = null;
        persistServers();
        WebView parked = warm.remove(server.key);
        warmSince.remove(server.key);
        if (parked != null) destroyWeb(parked); // 停着的那张可能已经登录失效了，别切回去给人看登录屏
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (wantsServerList()) showSetup(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST_CAMERA || pendingPermission == null) return;
        PermissionRequest request = pendingPermission;
        pendingPermission = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            request.grant(request.getResources());
        } else {
            request.deny(); // 用户拒了：必须结掉，页面才能拿到错误而不是一直等
        }
    }

    private boolean wantsServerList() {
        return "servers".equals(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SCREEN));
    }

    @Override
    protected void onPause() {
        super.onPause();
        backgroundedAt = SystemClock.elapsedRealtime();
        // 记住停在哪个路由，下次直接回到这里。渲染进程已经死了就别记了：那个页面刚把 renderer 打崩过，
        // 记下来下次冷启动又回到它，白崩一轮（崩溃循环时我们刚把 route 清掉，不能被这里写回来）。
        if (!renderGone) rememberRoute(web == null ? null : web.getUrl());
        // 让 cookie 落盘，免得重启后要重新配对。
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (backgroundedAt == 0) return;
        long away = SystemClock.elapsedRealtime() - backgroundedAt;
        backgroundedAt = 0;
        if (away < RESUME_RESYNC_AFTER_MS) return;
        // 回来先让页面自己重连一次：比整页 reload 便宜，也不动草稿和滚动位置。
        resyncPage("回到前台（离开了 " + (away / 1000) + " 秒）");
    }

    /**
     * 手机现在有没有任何可用网络。只判"有没有"（{@code getActiveNetwork() == null}），
     * 不要求 VALIDATED_INTERNET：局域网地址本来就可能没有外网，那也要能连。
     * 拿不到系统服务时按"有网"处理 —— 别让一次 API 失败把用户挡在门外。
     */
    private boolean hasNetwork() {
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            return manager == null || manager.getActiveNetwork() != null;
        } catch (Exception error) {
            Log.w(TAG, "查网络状态失败：" + error);
            return true;
        }
    }

    /**
     * 把其他服务器的连接先热起来：用 A 的时候，切到 B 少付 DNS+TCP+TLS 那 1–2 个 RTT。
     *
     * <p>只热每台的首选地址（门户/隧道那条）：局域网候选在换网络后本来就不可达，热了也是白热。
     * 不携带任何 cookie/凭据 —— 只是一次到 origin 的连接，不认 OpenCode 的登录。
     * 内核不支持（`WebViewFeature.PRECONNECT`）或多 Profile 不可用时静默跳过。
     *
     * <p>连接大约活 30 秒，所以下一次调用要有 20 秒的间隔；打开切换面板时用 {@code force=true}
     * 重新热一次（面板打开到点下去通常就几秒，正好赶在动手前）。
     */
    @SuppressLint("UnsafeOptInUsageError") // Profile#preconnect 目前仍是 experimental API
    private void preconnectOtherServers(boolean force) {
        if (web == null || renderGone || servers.size() < 2) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PRECONNECT)) return;
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return; // getProfile 的前提
        Profile profile;
        try {
            profile = WebViewCompat.getProfile(web);
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "拿不到 WebView Profile，跳过连接预热：" + error);
            return;
        }
        if (profile == null) return;
        long now = SystemClock.elapsedRealtime();
        for (String origin : preconnectTargets()) {
            Long last = preconnectedAt.get(origin);
            if (!force && last != null && now - last < PRECONNECT_REFRESH_MS) continue;
            try {
                profile.preconnect(origin);
                preconnectedAt.put(origin, now);
                Log.i(TAG, "预热连接：" + origin);
            } catch (Exception | LinkageError error) {
                Log.w(TAG, "预热连接失败：" + origin + "（" + error + "）");
            }
        }
    }

    /**
     * 要预热的地址：每台服务器的**首选** origin（局域网候选换网络后本来就不可达），跳过当前这台。
     * 单独拎出来是为了能在 JVM 上验"预热谁、不预热谁"，WebView 那一步在测试里跑不到。
     */
    List<String> preconnectTargets() {
        List<String> targets = new ArrayList<>();
        for (Server server : servers) {
            if (active != null && active.key.equals(server.key)) continue;
            if (server.origins.isEmpty()) continue;
            String origin = baseOf(normalize(server.origins.get(0)));
            if (origin != null) targets.add(origin);
        }
        return targets;
    }

    /**
     * 换网络后通知一次页面（系统一次切换会连报几个回调，所以去抖到 {@link #NETWORK_SETTLE_MS} 之后）。
     * 只关心"有网了"：断网时页面自己的重连循环本来就在跑，再推一次没有意义。
     */
    private void registerNetworkNudges() {
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (networkNudgePending) return;
                    networkNudgePending = true;
                    handler.postDelayed(networkNudge, NETWORK_SETTLE_MS);
                }
            };
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception error) {
            // 没拿到权限/厂商 ROM 不支持：少一条兜底而已，不能让它影响启动。
            Log.w(TAG, "注册网络变化回调失败：" + error);
            networkCallback = null;
        }
    }

    private void unregisterNetworkNudges() {
        handler.removeCallbacks(networkNudge);
        networkNudgePending = false;
        if (networkCallback == null) return;
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager != null) manager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
            // 已经注销/进程要退：没有可做的事
        }
        networkCallback = null;
    }

    /**
     * 让 Web UI 重新订阅一次事件流，不重载页面。上游把 window 上的 `online` 事件当成"网络恢复了"的哨兵：
     * 收到就断掉当前那条 SSE 再连（`packages/client/src/solid/connection.ts` 的 pageLifecycle），
     * 连上后的 `server.connected` 会让正在看的会话重新拉一遍消息（`runtime/server/sync.tsx`）。
     *
     * <p>壳只用这一条约定，不碰页面状态、不注入桥 —— 页面若哪天不认这个事件，这里最多是无害的空转。
     */
    void resyncPage(String reason) {
        if (web == null || renderGone || !webReady || active == null) return;
        // 删掉当前那台之后，WebView 里还留着旧 origin 的页面：不能再往它里面发事件
        if (!isTrustedOrigin(web.getUrl())) return;
        if (web.getVisibility() != View.VISIBLE) {
            resyncPending = true; // 人在管理页：等 resumeCurrent 回到页面时补一次
            return;
        }
        Log.i(TAG, "让页面重新订阅事件流：" + reason);
        web.evaluateJavascript("window.dispatchEvent(new Event('online'))", null);
    }

    private void applyLoadBarTop() {
        if (loadBar == null) return;
        if (!(loadBar.getLayoutParams() instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) loadBar.getLayoutParams();
        if (params.topMargin == loadBarTop) return;
        params.topMargin = loadBarTop;
        loadBar.setLayoutParams(params);
    }

    /** 悬浮球面板里那行状态：加载中就说加载到哪了，否则就是"重新加载当前页面"。 */
    private String refreshStateText() {
        if (loadBar != null && loadBar.getVisibility() == View.VISIBLE) {
            return "正在加载 " + Math.max(1, Math.min(loadPercent, 99)) + "% · 点这里重新加载";
        }
        return "重新加载当前页面";
    }

    private void updateRefreshState() {
        if (refreshState != null) refreshState.setText(refreshStateText());
    }

    /** 页面加载进度：走到 100% 收掉；页面不在台前（管理页/渲染进程没了）也收掉。 */
    void showLoading(int percent) {
        if (loadBar == null) return;
        if (renderGone || web == null || web.getVisibility() != View.VISIBLE) {
            hideLoading();
            return;
        }
        if (percent >= 100) {
            loadPercent = 100;
            loadBar.setVisibility(View.GONE);
            updateRefreshState();
            return;
        }
        loadPercent = Math.max(percent, 5); // 0% 画不出东西，见 load()
        loadBar.setProgress(loadPercent);
        loadBar.setVisibility(View.VISIBLE);
        updateRefreshState();
    }

    void hideLoading() {
        loadPercent = 0;
        if (loadBar != null) loadBar.setVisibility(View.GONE);
        updateRefreshState();
    }

    /**
     * 刷新当前页面。走和冷启动同一条加载路径：看门狗、失败后原地重试、候选链都还能接上，
     * 不是 WebView 自己的 reload()（那样失败时壳什么都不知道，也没法换地址）。
     */
    void reloadPage() {
        if (renderGone) return;
        if (!hasNetwork()) {
            // 页面还在眼前：别为了报错把它换掉 / 埋掉，说一句就够了。
            Toast.makeText(this, Failure.OFFLINE, Toast.LENGTH_SHORT).show();
            return;
        }
        cancelPageRetry();
        pageRetries = 0;
        String url = web == null ? null : web.getUrl();
        if (url != null && isTrustedOrigin(url)) {
            Log.i(TAG, "刷新：" + url);
            load(url);
            // load() 会把 webReady 清掉（新的一次加载）；但这是"刷新一张活页面"，
            // 失败时仍然该走原地重试，而不是直接丢掉深链回首页/管理页。
            pageWasLive = true;
            return;
        }
        // 没有可信页面（比如停在错误页/管理页）：当作重新连一次
        startAttempt();
    }

    /** 回当前这台服务器的首页：深链/会话页卡住时，一个确定能回去的落点（不是浏览历史的后退）。 */
    void goHome() {
        if (renderGone) return;
        // 正在配对/切换时 candidates() 是**这一轮**的新地址：首页也得从这里取，
        // 否则壳加载的是旧 active 的首页，而回调会被 isCurrentCandidate 当成陈旧结果丢掉。
        List<String> origins = !attemptOrigins.isEmpty()
                ? attemptOrigins
                : (active == null ? Collections.emptyList() : active.origins);
        if (origins.isEmpty()) return;
        cancelPageRetry();
        pageRetries = 0;
        routeAttempt = false; // 用户明确要首页：失败就按候选链走，不要再回到"上次那个页面"的重试分支
        candidate = 0;
        Log.i(TAG, "回首页：" + origins.get(0));
        load(origins.get(0));
        pageWasLive = true; // 和刷新一样：失败先原地重试一次，别立刻把人踢回连接页
    }

    /** 复制当前页面地址：在电脑上接着打开同一个会话（Web UI 自己没有这个入口）。 */
    void copyCurrentAddress() {
        String url = web == null ? null : web.getUrl();
        // 只复制可信的普通页面：配对落地页的 URL 里有一次性码，剪贴板别的 App 也能读。
        if (url == null || !isTrustedOrigin(url) || isConnectPage(url)) {
            Toast.makeText(this, "当前没有可复制的页面地址", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("opencode page", url));
        Toast.makeText(this, "地址已复制", Toast.LENGTH_SHORT).show();
    }

    /** 复制配对命令：用户可能在手机上 SSH 到电脑，直接粘过去就能跑。 */
    void copyPairCommand() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("opencode pair", "opencode pair --remote"));
        Toast.makeText(this, "已复制，去电脑上运行", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // fontScale/旋转这类（会重建 Activity）之后，人如果本来在管理页就还回管理页 ——
        // 否则重建会按 intent 冷启动，把人直接丢进服务器页面。
        outState.putBoolean(STATE_MANAGER, setup != null && setup.getVisibility() == View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        cancelWatchdog();
        unregisterNetworkNudges();
        cancelPageRetry();
        if (preview != null) {
            preview.dismiss();
            preview = null;
        }
        if (actions != null) {
            actions.dismiss();
            actions = null;
        }
        shutdown(redemptionExecutor);
        shutdown(probeExecutor);
        // 还在飞的弹窗探针：handler 里的延迟销毁会被下面一句清掉，所以这里直接收掉。
        for (WebView probe : probes) probe.destroy();
        probes.clear();
        handler.removeCallbacksAndMessages(null); // 排队中的撤销/返回提示/重试都别再打到要销毁的视图上
        if (fileCallback != null) {
            // 页面的 <input type=file> 会一直等这个回调，结不掉就再也不响应。
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        if (pendingPermission != null) {
            pendingPermission.deny();
            pendingPermission = null;
        }
        if (web != null) {
            // destroy() 的契约：先从视图树摘下来，之后不能再调它任何方法（渲染进程没了时更不能）。
            android.view.ViewParent parent = web.getParent();
            if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(web);
            if (!renderGone) web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        // 停着的那几张也要收掉，否则它们持有的 renderer 会一直挂着。
        for (WebView parked : warm.values()) destroyWeb(parked);
        warm.clear();
        warmSince.clear();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 只处理"进程还活着但内存紧张"。UI_HIDDEN 不丢：否则切出去回条消息再回来，另一台就要重新加载。
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            dropWarmPages("系统内存紧张");
        }
    }

    /** 丢掉停着的页面，只留当前这张。内存不够时用，不碰 active。 */
    private void dropWarmPages(String reason) {
        if (warm.isEmpty()) return;
        Log.i(TAG, "丢掉停着的页面（" + reason + "，" + warm.size() + " 张）");
        for (WebView parked : warm.values()) destroyWeb(parked);
        warm.clear();
        warmSince.clear();
    }

    @Override
    public void onBackPressed() {
        if (web == null) return;
        // 配对在飞：一次返回就是取消这次配对（码已经花掉了，晚到的结果必须丢掉），不是退出 App。
        if (redeemInFlight) {
            abortPairing();
            return;
        }
        if (web.getVisibility() == View.VISIBLE) {
            if (web.canGoBack() && isTrustedOrigin(web.getUrl())) {
                web.goBack();
                return;
            }
            // 已经在 app 根页面：再按一次才回管理页。第一次只提示一句，别让人不知道这个入口存在。
            if (!backOnceArmed) {
                backOnceArmed = true;
                handler.postDelayed(() -> backOnceArmed = false, BACK_ARM_MS);
                Toast.makeText(this, "再按一次返回：管理服务器（当前："
                        + (active == null ? "无" : displayName(active)) + "）", Toast.LENGTH_SHORT).show();
                return;
            }
            backOnceArmed = false;
            openManager(null);
            return;
        }
        // 人在管理页：有活页面就回它（不重新加载），否则才是退出 App。
        if (web.getUrl() != null && isTrustedOrigin(web.getUrl())) {
            resumeCurrent();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 连接

    /** 本次要依次尝试的地址：正在配对就用配对的，否则用当前服务器的。 */
    private List<String> candidates() {
        if (!attemptOrigins.isEmpty()) return attemptOrigins;
        return active == null ? Collections.<String>emptyList() : active.origins;
    }

    private void connect(String raw) {
        Pairing pairing = parse(raw);
        if (pairing == null) {
            rejectLink("这不是 opencode pair 的链接或二维码。");
            return;
        }
        if (pairing.reason != null) {
            rejectLink(pairing.reason);
            return;
        }
        lastPairLink = raw.trim();
        if (!hasNetwork()) {
            // 没网时兑换只会卡到超时；先把粘贴的链接留住，等有网了直接再点一次。
            showSetupKeepingLink(Failure.OFFLINE);
            return;
        }
        // 同一条码还在兑换中就别再发一次 —— 不论这次拿到的是二维码里的哪条地址：
        // 码是一次性的，两个请求抢会先花掉它，后到的 401 会把成功的结果顶掉。
        // （换一条别的码则允许：那会作废前一次尝试，见 startRedeem 的代际计数。）
        if (redeemInFlight && pairing.code.equals(pairingCode)) return;
        probedDead = Collections.emptySet();
        loadingUrl = null;
        attemptOrigins = pairing.origins;
        attemptKey = pairing.origins.get(0);
        pairingCode = pairing.code;
        routeAttempt = false;
        pendingToken = null;
        pendingTokenOrigin = null;
        candidate = 0;
        formExpanded = true; // 配对中要看得见表单（进度条、取消都在里面），失败回来也能直接改链接
        renderServers();
        // 先用 API 那条路兑换（拿 token 再进页面）；它失败时可能退回下面那条"直接加载 /auth/connect/"的老路。
        startRedeem();
    }

    /**
     * 链接本身有问题（形状不对 / 只给本机地址 / 旧格式）：标在输入框上（就近），
     * 同时保留状态行那句 —— 眼睛先落在输入框，但状态行也照旧说一遍。
     * 粘进来的原文不清掉：用户要在这上面改。
     */
    private void rejectLink(String message) {
        String kept = input == null ? "" : input.getText().toString();
        showSetup(message);
        if (input != null) {
            input.setText(kept);
            input.setSelection(kept.length());
        }
        if (inputField != null) inputField.setError(message);
    }

    /** 配对进行中按返回 / 点「取消」：这次不配了，晚到的结果一律丢掉（代际在 showSetup 里 +1）。 */
    void abortPairing() {
        // 用户可能只是想改一下再试：showSetup 会清输入框，这里先把粘的链接留住。
        String kept = input == null ? "" : input.getText().toString();
        // 取消配对要真的中止（清掉在飞的兑换），所以这条不能用 say 的"只回管理页"。
        showSetup("已取消这次配对。");
        if (input != null && !kept.isEmpty()) {
            input.setText(kept);
            input.setSelection(input.getText().length());
        }
        if (status != null) status.setTextColor(getColor(R.color.shell_text_muted));
    }

    /**
     * 打开管理页（添加 + 列表）：**先记住活页面的路由**，再把 WebView 藏起来。
     * 和 showSetup() 的区别：不作废在飞的配对、不 stopLoading、不把已加载好的页面判成失败 ——
     * 那个页面只是被藏起来了，随时 resumeCurrent() 就能原样回来。
     */
    void openManager(String message) {
        if (!renderGone) rememberRoute(web == null ? null : web.getUrl());
        managerOpen = true;
        // 排队中的重试还没发出去：取消它，页面此刻停在错误页上 —— 点当前那台必须重连。
        // 已经在飞的那次重试还没把页面救回来（webReady 已清、结果未定）：回来时也重连，
        // 别把中途状态（或错误页）原样翻出来。pageRetryInFlight 留着，onPageStarted 还要靠它解开 failedUrl。
        if (pageRetryInFlight) pageDead = true;
        dropPendingPageRetry();
        pageRetries = 0;
        hideLoading();
        // 人在管理页：还在等的那个首次响应不再替他做结论（否则 20 秒后看门狗会把页面抢回来）。
        // 加载本身不打断：切回去时那张页面还在，失败的话点当前那行会重连（见 resumeCurrent）。
        cancelWatchdog();
        loadingUrl = null;
        if (web != null) web.setVisibility(View.GONE);
        if (setup != null) setup.setVisibility(View.VISIBLE);
        status.setText(message == null ? "" : message);
        renderServers();
        renderBall();
    }

    /**
     * 点悬浮球：弹一个**服务器列表面板**（不直接换页，太容易误触）。
     * 列表里点哪台就切哪台，底部一条进「服务器管理」（加 / 改名 / 删）。
     *
     * <p>自绘而不是 `setItems`：系统单选列表把每台压成一行、还带一圈单选钮，
     * 名字和地址挤在一起看不出哪台是哪台（真机上验证过）。这里每台两行 —— 名字 + 短地址，
     * 当前那台用实心点 + accent 字。
     */
    private void showServerPreview() {
        // 打开面板通常就是"要切了"：这时候把其他服务器的连接重新热一遍，正好赶在点下去之前。
        preconnectOtherServers(true);
        final List<Server> list = new ArrayList<>(servers);
        if (list.isEmpty()) {
            openManager(null);
            return;
        }
        // 正文自己排版（对话框那圈默认内边距交给 setView 的 spacing 归零）：
        // 标题一行、每台两行、分隔线、"服务器管理"一行 —— 行是整宽的，水波纹才铺满。
        LinearLayout panel = new LinearLayout(this);
        panel.setId(R.id.preview_list);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(padded(text("切换服务器", 13, getColor(R.color.shell_text_muted), false, Gravity.START),
                20, 16, 20, 6), row());
        for (Server server : list) {
            panel.addView(previewRow(server, active != null && active.key.equals(server.key)));
        }
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.shell_border_muted));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerParams.topMargin = dp(8);
        dividerParams.leftMargin = dp(20);
        dividerParams.rightMargin = dp(20);
        panel.addView(divider, dividerParams);

        // 刷新：Web UI 自己没给壳这一层留入口（整页重开是唯一办法），加载慢的时候顺便把进度说出来。
        LinearLayout refreshRow = new LinearLayout(this);
        refreshRow.setId(R.id.preview_refresh);
        refreshRow.setOrientation(LinearLayout.VERTICAL);
        refreshRow.setPadding(dp(20), dp(12), dp(20), dp(12));
        refreshRow.setBackgroundResource(selectableBackground());
        refreshRow.addView(text("刷新页面", 14, getColor(R.color.shell_accent), false, Gravity.START));
        refreshState = text(refreshStateText(), 13, getColor(R.color.shell_text_muted), false, Gravity.START);
        refreshRow.addView(refreshState);
        refreshRow.setOnClickListener(v -> {
            dismissPreview();
            reloadPage();
        });
        panel.addView(refreshRow, row());

        // 前进/后退走的是页面自己的历史（WebView 的 back-forward list；SPA 的 pushState 也在里面）。
        // "回首页"不放在这儿：它跟页面导航不是一回事，页面真卡住时用上面的「刷新页面」；
        // 而且两颗球/一次误点的代价比一条导航动作大。
        LinearLayout nav = new LinearLayout(this);
        nav.setId(R.id.preview_nav);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        TextView back = previewAction("← 后退", R.id.preview_back, R.color.shell_text, this::pageBack);
        TextView forward = previewAction("前进 →", R.id.preview_forward, R.color.shell_text, this::pageForward);
        setNavEnabled(back, web != null && web.canGoBack());
        setNavEnabled(forward, web != null && web.canGoForward());
        nav.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        nav.addView(forward, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(nav, row());

        panel.addView(previewAction("复制当前地址", R.id.preview_copy, R.color.shell_text,
                this::copyCurrentAddress));
        // 扫码添加：不用先回管理页，扫完回连接页配对（背后活页面由 showSetup 保着，取消还在）。
        panel.addView(previewAction("扫码添加服务器", R.id.preview_scan, R.color.shell_text, this::scan));

        // 底部这一条是"去管理页"，不是弹窗的确认按钮：左对齐 + 一个 ›，别做成 M3 那种右对齐文字按钮。
        TextView manage = padded(text("服务器管理 ›", 14, getColor(R.color.shell_accent), false, Gravity.START),
                20, 14, 20, 16);
        manage.setBackgroundResource(selectableBackground());
        manage.setOnClickListener(v -> {
            dismissPreview();
            openManager(null);
        });
        panel.addView(manage, row());

        // 整个面板可滚：服务器多、字体放大或横屏时，刷新/管理这些动作不能被挤出屏幕。
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.setId(R.id.preview_scroll);
        panelScroll.addView(panel);
        preview = edgeToEdge(new MaterialAlertDialogBuilder(this), panelScroll).create();
        preview.show();
    }

    /** 没有上一页/下一页时把动作压淡并禁用，不给一个点了没反应的按钮。 */
    private void setNavEnabled(TextView view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
    }

    /** 页面自己的历史后退（不是壳的页面栈）。 */
    void pageBack() {
        if (web == null || !web.canGoBack()) return;
        web.goBack();
    }

    /** 页面自己的历史前进。 */
    void pageForward() {
        if (web == null || !web.canGoForward()) return;
        web.goForward();
    }

    /** 面板里的单行动作：点完先关面板再干活（不叠对话框）。 */
    private TextView previewAction(String label, int id, int colorRes, Runnable action) {
        TextView row = padded(text(label, 14, getColor(colorRes), false, Gravity.START), 20, 12, 20, 12);
        row.setId(id);
        row.setBackgroundResource(selectableBackground());
        row.setOnClickListener(v -> {
            dismissPreview();
            action.run();
        });
        return row;
    }

    /** `●/○ + 名字`：**只有圆点**上 accent（当前那台），名字保持正文色。 */
    private CharSequence dotLabel(String name, boolean current) {
        SpannableString label = new SpannableString((current ? "● " : "○ ") + name);
        label.setSpan(new ForegroundColorSpan(getColor(current ? R.color.shell_accent : R.color.shell_text_muted)),
                0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return label;
    }

    /**
     * 面板里的一台：`● 名字` / `短地址 · 当前`。整行可点 = 切过去。
     *
     * <p>名字保持正文色、只有圆点是 accent，再在地址后面写一句"当前"：当前是哪台不从颜色上读
     * （只靠颜色区分对色觉障碍不友好，也吵）。
     */
    private View previewRow(Server server, boolean current) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        line.setPadding(dp(20), dp(10), dp(20), dp(10));
        line.setBackgroundResource(selectableBackground());
        line.addView(text(dotLabel(displayName(server), current), 14,
                getColor(R.color.shell_text), false, Gravity.START));
        // 地址不能省：两台都在同一个局域网时，光看名字分不出哪台是哪台。
        line.addView(text(shortAddress(nameOf(server.key)) + (current ? " · 当前" : ""), 13,
                getColor(R.color.shell_text_muted), false, Gravity.START));
        line.setOnClickListener(v -> {
            dismissPreview();
            if (current) {
                resumeCurrent();
            } else {
                switchTo(server);
            }
        });
        return line;
    }

    private void dismissPreview() {
        refreshState = null;
        if (preview == null) return;
        preview.dismiss();
        preview = null;
    }

    /** 给一行文字上内边距（dp）。 */
    private TextView padded(TextView view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
        return view;
    }

    /** 行/条目的水波纹（`?android:selectableItemBackground`）。 */
    private int selectableBackground() {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    /** 回当前那台：WebView 里已经是它 → 直接显示回来，不重新加载（滚动位置、没提交的草稿都还在）。 */
    void resumeCurrent() {
        String url = web == null ? null : web.getUrl();
        if (web == null || url == null || !isTrustedOrigin(url) || pageDead) {
            // 没有活页面（冷启动、崩溃重建），或者上次已经把候选链走完、屏幕上只剩 Chromium 的错误页：
            // 这里必须重新连，而不是把错误页原样翻出来（那看起来就像"点了没反应"）。
            startAttempt();
            return;
        }
        managerOpen = false;
        setup.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        renderBall();
        if (resyncPending) {
            resyncPending = false;
            resyncPage("回到页面");
        }
    }

    /**
     * API 那条路兑换配对码：{@code <origin>/auth/connect/<code>} 只要 Accept 里没有 text/html 就回
     * {@code {"token":…}}（上游 handlers/server.ts 里的分流）。拿到之后自己写 cookie 再进首页 ——
     * 不用赌 302 的中间环节，失败原因也能按状态码分清（见 {@link Redeem.Status}）。
     */
    @android.annotation.SuppressLint("SetTextI18n") // 单语 UI，文案不抽 strings（刻意不抽 strings）
    private void startRedeem() {
        final List<String> origins = new ArrayList<>(candidates());
        if (origins.isEmpty()) return;
        final String code = pairingCode;
        redeemInFlight = true;
        setBusy(true);
        final int generation = ++redeemGeneration;
        status.setTextColor(getColor(R.color.shell_text_muted)); // 进行中是中性消息；失败由 showSetup 标红
        status.setText("正在配对" + addressLabel(origins.get(0)) + " " + nameOf(origins.get(0)) + "…");
        redemptionExecutor.execute(() -> {
            Redeem.RedeemOutcome outcome =
                    Redeem.redeem(origins, code, redeemAttempt, redeemVerify, Redeem.HEDGE_MS);
            postToMain(() -> onRedeemFinished(outcome, generation));
        });
    }

    /** 兑换结果（主线程）。参数收成一个对象 + 代际，是为了能在 JVM 上直接测这条分支。 */
    void onRedeemFinished(Redeem.RedeemOutcome outcome, int generation) {
        // 晚到的结果：Activity 已经没了，或者用户在等待期间切了服务器/按了返回/又发了另一条码。
        // 前者碰 WebView 会崩，后者会把用户从一个地址拽到另一个地址。
        if (generation != redeemGeneration || isDestroyed() || web == null) return;
        redeemInFlight = false;
        // 进度条不在这里收：下面每条分支最后都落到 load()（进页面）或 showSetup()（回连接页），那两处才是漏斗
        String winner = outcome.origin;
        String token = outcome.token;
        if (token != null && outcome.verified) {
            CookieManager.getInstance().setCookie(winner, Redeem.cookieValue(winner, token, System.currentTimeMillis()));
            CookieManager.getInstance().flush();
            pendingToken = token;
            pendingTokenOrigin = winner;
            candidate = Math.max(0, candidates().indexOf(winner));
            Log.i(TAG, "配对码已兑换（API）：" + winner);
            load(winner + "/"); // cookie 已就位：直接进首页，不用再走 /auth/connect
            return;
        }
        if (token != null) {
            // 换到了 token 但这条地址明确拒了它（401/403）：不写 cookie、不落盘，让用户重来
            // （码已经花掉了，没法回退）
            Log.i(TAG, "兑换到 token 但地址拒了它（" + winner + "），不写 cookie");
            showSetup("配对码换到了 token，但这条地址拒绝了这个 token（可能不是 OpenCode 服务）。请重新运行 opencode pair。");
            return;
        }
        if (outcome.expired) {
            showSetup("配对码已失效或已被使用，请在你的电脑上重新运行 opencode pair。");
            return;
        }
        if (outcome.notOpencode) {
            // 200 但不是 API：多半打到了别的站（OpenCode 的 SPA 对未知路径也回 index.html）。
            // 只要有一条地址是这种，就不退回"加载页面"那条路 —— 那只会把一个不相干的网站装进壳里。
            showSetup("这个地址不是 OpenCode 服务（" + (outcome.last == null ? "" : outcome.last.detail) + "）。请检查地址和端口。");
            return;
        }
        if (outcome.last != null && outcome.last.status == Redeem.Status.SERVICE_FAILED) {
            showSetupKeepingLink("OpenCode 的后台服务起不来（503 service_failed）。在电脑上运行 opencode service restart，"
                    + "再点下面的「连接」试一次（链接已经留在输入框里）。");
            return;
        }
        if (outcome.last != null && outcome.last.status == Redeem.Status.STARTING) {
            showSetupKeepingLink("opencode 服务正在启动（503）。几秒后再点下面的「连接」试一次"
                    + "（链接已经留在输入框里，码还没用掉）。");
            return;
        }
        // 纯网络层失败：退回"直接加载 /auth/connect/<code>"那条老路（特殊代理部署可能只让页面这条路通）
        Log.i(TAG, "API 兑换没成功（" + (outcome.last == null ? "没有响应" : outcome.last.status + " " + outcome.last.detail) + "），改用页面加载");
        loadCandidate();
    }

    /** 连接页上点某台服务器。 */
    private void switchTo(Server server) {
        String live = web == null ? null : web.getUrl();
        // 点的是**当前**这台、而且页面正停在它上面：只把页面显示回来，不重新加载（滚动、草稿都还在）
        if (active != null && active.key.equals(server.key) && live != null
                && setup.getVisibility() == View.VISIBLE && isTrustedOrigin(live)) {
            resumeCurrent();
            return;
        }
        if (!renderGone) rememberRoute(live); // 离开这台之前先把它的路由记下来（不然切回来是上一次退后台的位置）

        switchAt = SystemClock.elapsedRealtime();
        switchVisibleAt = 0;
        switchWasWarm = false;
        // 这台刚被切走过、页面还活着：直接换回来，不走一次 SPA 冷启动 —— 这是整个壳最值钱的一次优化。
        WebView parked = warm.remove(server.key);
        if (parked != null && !renderDead.contains(parked)) {
            switchWasWarm = true;
            parkCurrent();          // active 还是旧的那台，key 用它
            adopt(server, parked);
            return;
        }
        if (parked != null) destroyWeb(parked); // 渲染进程没了：丢掉，老实重新加载

        // 冷切：当前这页如果还活着就先停着，给新服务器换一张新的 WebView。
        // 两页共用一张的话，切回来还是得重新加载，等于没优化。
        boolean parkedCurrent = parkCurrent();
        webReady = false; // 旧页面的状态到此为止：新的一张还什么都没加载
        redeemGeneration++; // 换服务器就作废在飞的兑换/探测，否则晚到的结果会把用户拽回上一个地址
        redeemInFlight = false;
        probedDead = Collections.emptySet();
        loadingUrl = null;
        attemptOrigins = Collections.emptyList();
        attemptKey = "";
        pairingCode = "";
        routeAttempt = false;
        active = server;
        candidate = 0;
        prefs.edit().putString(KEY_ACTIVE, server.key).apply();
        if (parkedCurrent) {
            web = buildWebView();
            root.addView(web, 0, fill());
        } else if (web != null) {
            // 复用同一张 WebView 加载另一个 origin：留着上一台的历史，返回键会退到一个已经不可信的 origin
            // （onPageStarted 判不可信 → stopLoading + 甩给系统浏览器），"返回键双按进管理页"也会失效。
            web.clearHistory();
            historyClears++;
        }
        renderServers();
        startAttempt();
    }

    /**
     * 把当前这页摘下来存进 warm，切走时不再销毁它。页面不活（没加载成功、已经判死、停在错误页）
     * 就不留 —— 留一张错误的页面，切回来只会把错误页原样翻出来。
     */
    private boolean parkCurrent() {
        if (web == null || renderGone || active == null) return false;
        String url = web.getUrl();
        if (!webReady || pageDead || url == null || !isTrustedOrigin(url)) return false;
        WebView view = web;
        web = null;
        cancelWatchdog();
        cancelPageRetry();
        hideLoading();
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view.setVisibility(View.GONE);
        WebView previous = warm.put(active.key, view);
        warmSince.put(active.key, SystemClock.elapsedRealtime());
        if (previous != null && previous != view) destroyWeb(previous);
        // 上限之外按"最久没用到"丢掉（LinkedHashMap 的插入顺序够用：这里最多两张）
        while (warm.size() > WARM_LIMIT) {
            Iterator<Map.Entry<String, WebView>> entries = warm.entrySet().iterator();
            if (!entries.hasNext()) break;
            Map.Entry<String, WebView> oldest = entries.next();
            entries.remove();
            warmSince.remove(oldest.getKey());
            destroyWeb(oldest.getValue());
        }
        return true;
    }

    /** 把一张停着的页面挂回来：不 loadUrl、不清历史，滚动位置和没提交的草稿都还在。 */
    private void adopt(Server server, WebView view) {
        if (web != null && web != view) destroyWeb(web); // 当前那页没活到能停下的程度：直接收掉
        long now = SystemClock.elapsedRealtime();
        Long parkedAt = warmSince.remove(server.key);
        active = server;
        prefs.edit().putString(KEY_ACTIVE, server.key).apply();
        web = view;
        renderDead.remove(view);
        root.addView(view, 0, fill());
        view.setVisibility(View.VISIBLE);
        setup.setVisibility(View.GONE);
        redeemGeneration++;
        redeemInFlight = false;
        probedDead = Collections.emptySet();
        loadingUrl = null;
        attemptOrigins = Collections.emptyList();
        attemptKey = "";
        pairingCode = "";
        routeAttempt = false;
        candidate = 0;
        attemptFinished = true; // 这张页面早就加载完了：迟到的回调不许再改状态
        failedUrl = null;
        pageDead = false;
        pageWasLive = false;
        managerOpen = false;
        webReady = true;
        authHintShown = false;
        resyncPending = false;
        hideLoading();
        renderServers();
        renderBall();
        if (switchAt != 0) {
            Log.i(TAG, "切台：热切挂回 " + (now - switchAt) + "ms → " + server.key);
            switchAt = 0;
        }
        // 短时间的来回切，页面一直在跑、SSE 没断：不打扰它，免得白花一个 RTT 还可能把滚动位置顶掉。
        // 停久了（或不知道停了多久）才让它重订一次事件流，防止半开的连接。
        if (parkedAt == null || now - parkedAt >= WARM_RESYNC_AFTER_MS) {
            resyncPage("切回停着的页面（停了 " + (parkedAt == null ? "?" : (now - parkedAt) / 1000) + " 秒）");
        }
        preconnectOtherServers(false); // 回到这台了：顺手把另一台的连接热着
    }

    /** 收掉一张 WebView；渲染进程已经没了的，只 destroy，不碰它的其他方法。 */
    private void destroyWeb(WebView view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        boolean dead = renderDead.remove(view);
        if (!dead) {
            try {
                view.loadUrl("about:blank");
            } catch (Exception ignored) {
                // 渲染进程刚好没了：下面的 destroy 仍然要执行
            }
        }
        try {
            view.destroy();
        } catch (Exception ignored) {
            // destroy() 只保证调一次；这里已经收尾，异常不该影响切台
        }
    }

    /**
     * 页面触发的下载。只处理已配对服务器上的 http(s)：带上会话 cookie 交给系统 DownloadManager。
     * blob:/data: 是 Web UI 自己在页面里生成的，没有文件可交给系统，只能说清楚。
     */
    private void onDownload(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
        String scheme = url == null ? "" : Uri.parse(url).getScheme();
        scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            Toast.makeText(this, "这个下载方式壳里不支持：要保存文件请在电脑上打开。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isTrustedOrigin(url)) {
            Toast.makeText(this, "只下载已配对服务器上的文件。", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) request.addRequestHeader("Cookie", cookie); // 受保护的文件也要带上会话
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            request.setTitle(name);
            request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            // API 29 起写公共 Downloads 不需要存储权限；更老的系统没有权限就退回应用自己的目录。
            boolean publicDir = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (publicDir) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("系统没有下载服务");
            manager.enqueue(request);
            Toast.makeText(this, "已开始下载：" + name, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Log.w(TAG, "下载失败：" + error);
            Toast.makeText(this, "下载失败：" + error.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 输入框的描边：不聚焦是 token 的发丝环，聚焦整圈变 accent（上游 text-input.css 那套）。 */
    private ColorStateList inputStroke() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[]{}},
                new int[]{getColor(R.color.shell_accent), getColor(R.color.shell_border)});
    }

    /** 给一台服务器改名（对齐上游的 displayName：名字只存客户端这边，不往服务器写）。 */
    private void promptRename(Server server) {
        TextInputLayout box = new TextInputLayout(this);
        box.setHint("名字");
        box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        box.setBoxStrokeColorStateList(inputStroke());
        box.setHelperText("留空就回到「服务器 " + (server.slot > 0 ? server.slot : 1) + "」"); // 别把说明塞进标题（标题要短、要能当标题用）
        float radius = getResources().getDimension(R.dimen.shell_card_radius);
        box.setBoxCornerRadii(radius, radius, radius, radius);
        EditText field = new TextInputEditText(this);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(NAME_MAX)});
        field.setText(displayName(server));
        field.setSelectAllOnFocus(true);
        field.setSingleLine(true);
        box.addView(field);
        LinearLayout wrap = new LinearLayout(this);
        int pad = dp(24); // 跟 M3 对话框标题那圈内边距对齐（不然标题和输入框差 4dp，看得出）
        wrap.setPadding(pad, dp(8), pad, 0);
        wrap.addView(box, row());
        AlertDialog dialog = edgeToEdge(new MaterialAlertDialogBuilder(this), wrap)
                .setTitle("改名")
                .setPositiveButton("保存", (d, which) -> rename(server.key, field.getText().toString()))
                .setNegativeButton("取消", null)
                .create();
        // **点外面不许关**：键盘弹着的时候那一圈很容易点到，改到一半的名字就这么没了
        // （真机反馈的"交互时有时候会突然消失"）。要退出就按「取消」或返回键。
        dialog.setCanceledOnTouchOutside(false);
        // 弹出来就把光标放进输入框、拉起键盘：大字体/横屏下不用先"点一下"才能改。
        field.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
    }

    private void forget(Server server) {
        WebView parked = warm.remove(server.key);
        warmSince.remove(server.key);
        if (parked != null) destroyWeb(parked); // 服务器都没了，别留着一张还带着它会话的页面
        redeemGeneration++; // 删掉/换了服务器：在飞的兑换/探测结果一律丢掉
        redeemInFlight = false;
        probedDead = Collections.emptySet();
        loadingUrl = null;
        boolean wasActive = active != null && active.key.equals(server.key);
        int index = servers.indexOf(server);
        servers.remove(server);
        if (wasActive) {
            active = servers.isEmpty() ? null : servers.get(0);
            attemptOrigins = Collections.emptyList();
            attemptKey = "";
            pairingCode = "";
        }
        persistServers();
        renderServers();
        if (wasActive && active != null) {
            // 删的是当前那台：留在管理页，不自动加载剩下的（别把人拽进他没点过的页面，见 docs/design.md）
            say("已移除 " + displayName(server) + "，当前换成 " + displayName(active) + "（点它进入）");
        } else {
            say("已删除 " + nameOf(server.key));
        }
        showUndo(server, index, wasActive);
    }

    /** 删除后的撤销条：5 秒内可以把条目放回原位置（当前那台连"当前"一起还回去）。 */
    @android.annotation.SuppressLint("SetTextI18n")
    private void showUndo(Server server, int index, boolean wasActive) {
        if (undoBar == null || server == null) return;
        undoServer = server;
        undoIndex = Math.max(0, index);
        undoWasActive = wasActive;
        undoText.setText("已移除「" + displayName(server) + "」 · 电脑上那个 opencode 还在跑");
        undoBar.setVisibility(View.VISIBLE);
        handler.removeCallbacks(undoExpiry);
        handler.postDelayed(undoExpiry, UNDO_MS);
    }

    /** 撤销那次移除（测试直接调它）。恢复后**不** loadUrl：留在管理页。 */
    void undoRemove() {
        if (undoServer == null) return;
        Server server = undoServer;
        String name = displayName(server);
        servers.add(Math.min(undoIndex, servers.size()), server);
        if (undoWasActive || active == null) active = server;
        persistServers();
        renderServers();
        dismissUndo();
        say("已恢复「" + name + "」");
    }

    private void dismissUndo() {
        handler.removeCallbacks(undoExpiry);
        undoServer = null;
        undoWasActive = false;
        if (undoBar != null) undoBar.setVisibility(View.GONE);
    }

    /**
     * 回到主线程跑：后台线程上（生产）就是 handler.post；已经在主线程上就直接跑（测试里探测执行器是
     * inline 的，这样不会平白多一次 looper 往返，用例也就还能同步断言）。
     */
    private void postToMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    /** 销毁时把壳自己的线程收掉（测试里这两个字段会被换成直接执行的桩，不是 ExecutorService）。 */
    private static void shutdown(Executor executor) {
        if (executor instanceof java.util.concurrent.ExecutorService) {
            ((java.util.concurrent.ExecutorService) executor).shutdownNow();
        }
    }

    /**
     * 并行探一遍候选地址，把"连不上"的记下来（见 {@link Probe}）。这条路径**不带凭据**，
     * 也不影响正在进行的加载——只有下面 {@link #applyProbeVerdicts} 几个条件同时成立时才提前换代。
     */
    private void probeCandidates() {
        final Server server = active;
        if (server == null || candidates().size() < 2) return; // 只有一条地址：没什么可挑的
        final List<String> list = new ArrayList<>(candidates());
        probeExecutor.execute(() -> {
            Map<String, Probe.Liveness> verdicts = probeAll.apply(list);
            postToMain(() -> {
                if (isDestroyed() || web == null || active != server) return; // 晚到的探测结果：整条丢掉
                probedDead = new HashSet<>(Probe.dead(list, verdicts));
                applyProbeVerdicts(list, verdicts);
            });
        });
    }

    /**
     * 探测说"当前这条地址连不上"时，要不要现在就换下一条？四个条件都成立才换：
     *
     * <ol>
     *   <li>页面还在等第一个响应（看门狗还在）——已经开始渲染的地址不动它，"慢"不等于"坏"；</li>
     *   <li>当前这条确实探到连不上；</li>
     *   <li>还有别的地址是活的——否则跳过去也只是换个死胡同；</li>
     *   <li>当前这条**不是**我们存着会话的那条——凭据只在自己被兑换出来的地址上有效（见 docs/design.md），
     *       把一个可能只是慢的、带着会话的地址丢掉，比多等几秒更糟。</li>
     * </ol>
     *
     * <p>不满足时什么都不做：看门狗照旧兜底（行为和不做 B 时一模一样）。
     */
    private void applyProbeVerdicts(List<String> list, Map<String, Probe.Liveness> verdicts) {
        if (watchdog == null || loadingUrl == null) return;
        List<String> active = candidates();
        if (candidate < 0 || candidate >= active.size()) return;
        String current = baseOf(normalize(active.get(candidate)));
        if (current == null || !probedDead.contains(active.get(candidate))) return;
        if (current.equals(sessionOrigin())) return; // 带着会话的那条：等看门狗自己判
        if (!Probe.anyAliveOtherThan(list, verdicts, active.get(candidate))) return;
        Log.i(TAG, "探到 " + current + " 连不上，提前换下一条");
        cancelWatchdog();
        candidateUnresponsive(loadingUrl);
    }

    /** 一个候选地址没在等到的响应：记住它这次死了，然后换下一条（看门狗和探测提前换代共用这一条路径）。 */
    private void candidateUnresponsive(String url) {
        failedUrl = url;
        failCandidate(Failure.describe(Failure.TIMEOUT, null, hasNetwork()) + "\n" + url);
    }

    /** 我们存着会话的那条 origin 的 base（凭据只对它有效）；没有会话时返回空串。 */
    private String sessionOrigin() {
        String origin = pendingTokenOrigin != null
                ? pendingTokenOrigin
                : (active == null ? null : active.tokenOrigin);
        if (origin == null) return "";
        String base = baseOf(normalize(origin));
        return base == null ? "" : base;
    }

    private void loadCandidate() {
        List<String> list = candidates();
        if (list.isEmpty()) return;
        String origin = list.get(candidate);
        // 兑换成功过（pendingToken 在）说明码已经花掉了：这里只重试首页，别拿一个用过的码再请求一次
        // —— 那会得到"码失效"，把真正的原因（首页没加载上）盖掉。
        boolean replayCode = pendingToken == null && !pairingCode.isEmpty();
        load(replayCode ? origin + CONNECT_PATH + pairingCode : origin);
    }

    /** 当前地址没连上：还有备选就换下一条，否则把原因显示出来。 */
    private void failCandidate(String message) {
        attemptFinished = true;
        // 这次尝试到此为止：还在排队/在飞的原地重试也一并作废，否则它醒来时会去解开 failedUrl、
        // 和正在进行的下一条候选/看门狗抢状态（重试被这里终结时 pageRetryInFlight 就漏了）。
        cancelPageRetry();
        // 人在管理页：后台那次加载的结论只写到状态行，不许换页、也不许把 WebView 抢回来。
        if (managerOpen) {
            cancelWatchdog();
            loadingUrl = null;
            pageDead = true; // 屏幕上没有页面了：点当前那台要重连，不能把 Chromium 的错误页翻出来
            if (status != null) {
                status.setTextColor(getColor(R.color.shell_status)); // 失败就是失败，别用 say() 的中性灰
                status.setText(message);
            }
            return;
        }
        if (routeAttempt) {
            // "上次那个页面"连不上（换了网络、地址没了）：直接回这台服务器的首页，别占用候选链的顺序。
            // 这不是"静默重来"：告诉用户上次停的地方打不开，免得以为状态丢了。
            routeAttempt = false;
            candidate = 0;
            Log.i(TAG, "上次的页面打不开，回首页");
            if (!managerOpen) {
                Toast.makeText(this, "上次停的页面打不开了，先回首页。", Toast.LENGTH_SHORT).show();
            }
            loadCandidate();
            return;
        }
        // 往前走，但跳过这次已经探到"连不上"的地址（省掉它们各自一次看门狗）
        int next = candidate + 1;
        List<String> list = candidates();
        while (next < list.size() && probedDead.contains(list.get(next))) next++;
        if (next < list.size()) {
            candidate = next;
            // 换地址是壳自己的动作，界面上只闪一下进度线看不出发生了什么 —— 说清换成哪一条。
            if (!managerOpen && list.size() > 1) {
                Toast.makeText(this, "这条地址连不上，改试第 " + (next + 1) + " 条：" + candidateLabel(list.get(next)),
                        Toast.LENGTH_SHORT).show();
            }
            loadCandidate();
            return;
        }
        // 配对失败不落盘；已经确认过的 active 保持不变，连接页还能切回去。
        // 出问题时这一行是唯一能看的东西：adb logcat -s OpenCodeClient
        Log.i(TAG, "连接失败：" + message);
        pageDead = true; // 屏幕上现在只剩 Chromium 的错误页：点当前那台要重连
        showSetup(message); // showSetup 会清掉进行中的尝试
    }

    /** 加载失败。回调里只取 url + 一句话原因，拆成字符串好在 JVM 上测这条路径。 */
    void onLoadFailed(String url, String description) {
        onLoadFailed(url, description, Failure.UNKNOWN);
    }

    /**
     * 主文档加载失败。{@code code} 是 {@code WebResourceError.getErrorCode()}（测试里直接给
     * {@link Failure} 的常量）：壳把它翻译成人话，别把 `net::ERR_*` 原样甩给用户。
     */
    void onLoadFailed(String url, String description, int code) {
        // 这个 URL 这次已经判过死了（看门狗/上一次错误）：它迟到的回调不许再动正在进行的加载。
        if (url != null && url.equals(failedUrl)) return;
        if (!isCurrentCandidate(url)) return; // 上一次加载的陈旧回调
        boolean online = hasNetwork();
        String reason = Failure.describe(code, description, online);
        // 已经在用 Web UI 时先原地重试一次：服务器重启、锁屏回来、网络闪断都会走到这里，
        // 直接把人踢回管理页等于丢掉当前页面（还丢草稿）。只试一次，失败照旧走候选链。
        // 用户主动刷新一张活页面也算（load 已经把 webReady 清了，靠 pageWasLive 记住）。
        // 手机真没网时重试没有意义：直接说清楚，别多等一轮。
        if (online && !managerOpen && (webReady || pageWasLive) && url != null && pageRetries < 1) {
            pageRetries++;
            cancelWatchdog();
            failedUrl = url; // 重试前这段窗口里，那个 URL 的迟到回调别再当成成功
            Log.i(TAG, "页面加载失败，原地重试一次：" + url + "（" + description + "）");
            Toast.makeText(this, "连接中断，正在重试…", Toast.LENGTH_SHORT).show();
            schedulePageRetry(url);
            return;
        }
        cancelWatchdog();
        failedUrl = url; // Chromium 还会给这个 URL 补一个 onPageFinished，别让它被当成成功
        failCandidate(reason + "\n" + url);
    }

    private void schedulePageRetry(String url) {
        cancelPageRetry();
        pageRetry = () -> {
            pageRetry = null;
            if (renderGone || web == null || active == null) return;
            // 这中间人可能已经按返回回管理页了：别把他拽回页面
            if (web.getVisibility() != View.VISIBLE) return;
            pageRetryInFlight = true;
            load(url, ROUTE_TIMEOUT_MS);
        };
        handler.postDelayed(pageRetry, PAGE_RETRY_DELAY_MS);
    }

    private void cancelPageRetry() {
        pageRetryInFlight = false;
        if (pageRetry == null) return;
        handler.removeCallbacks(pageRetry);
        pageRetry = null;
    }

    /**
     * 只收"还没发出去"的那次重试，并且把它救的那张已经报错的页面判死。
     * 用在"人要离开页面"的入口（进管理页/回连接页）：重试被取消后页面还停在错误页上，
     * 不判死的话点当前那台只会把 Chromium 的错误页原样翻出来。
     */
    private void dropPendingPageRetry() {
        if (pageRetry == null) return;
        pageDead = true;
        handler.removeCallbacks(pageRetry);
        pageRetry = null;
    }

    /**
     * 主文档的 HTTP 错误。配对页 4xx 是码的问题（URL 停在 /auth/connect/，onPageFinished 会说"失效"）；
     * 其他页面的 4xx/5xx 以前被直接忽略，于是 Cloudflare/反代的错误页被 onPageFinished 当成"已连接"——
     * 球在、看着正常，其实什么都没连上。这里按"有没有一张已经在用的页面"分开处理。
     */
    void onHttpError(String url, int statusCode) {
        if (url != null && url.equals(failedUrl)) return;
        if (!isCurrentCandidate(url)) return;
        if (statusCode < 400) return;
        if (isConnectPage(url) && statusCode < 500) return; // 4xx：页面自己留着，由 onPageFinished 判"失效"
        if (webReady) {
            // 已经在用的 UI：一次站内导航/刷新被拒，不该把人踢回连接页（见
            // http5xxOnTheAppItselfDoesNotKillTheLiveSession）。但主文档 401/403 说明这份登录
            // 已经被撤销 —— 页面会停在登录屏，壳这边得说一句，否则用户不知道要重新配对。
            if ((statusCode == 401 || statusCode == 403) && !authHintShown) {
                authHintShown = true;
                Toast.makeText(this, "服务器不认这份登录了。回连接页重新配对。", Toast.LENGTH_LONG).show();
            }
            Log.i(TAG, "页面里的请求返回 " + statusCode + "：" + url);
            return;
        }
        // 冷加载/配对落地页拿到的是错误页：这不是"连上了"，别让它走到 onPageFinished 的 webReady = true，
        // 更别把这一台写进列表。
        cancelWatchdog();
        failedUrl = url;
        failCandidate(Failure.describeHttp(statusCode) + "\n" + url);
    }

    /**
     * 渲染进程没了。不处理的话系统会直接把 app 进程杀掉（默认行为是"要求系统终止"），用户看到闪退。
     * 重建 Activity 从 prefs 恢复；但连续崩（同一个页面把 renderer 打崩）时退到连接页，别无限闪屏。
     */
    void onRenderGone(boolean crashed) {
        long now = System.currentTimeMillis();
        renderGoneStreak = now - lastRenderGoneAt < 15_000 ? renderGoneStreak + 1 : 1;
        lastRenderGoneAt = now;
        Log.i(TAG, "渲染进程没了（crashed=" + crashed + "，连续第 " + renderGoneStreak + " 次），重建 WebView");
        hideLoading();
        if (renderGoneStreak >= 3) {
            renderGoneStreak = 0;
            startOnSetup = true;
            if (active != null && active.route != null) {
                active.route = null; // 别再自动回到那个页面
                persistServers();
            }
        }
        renderGone = true;
        cancelWatchdog(); // 已经排队的看门狗会对死掉的 WebView 调 loadUrl
        recreate();
    }

    private void load(String url) {
        load(url, FIRST_RESPONSE_TIMEOUT_MS);
    }

    private void load(String url, long timeoutMs) {
        if (renderGone) return; // 渲染进程已经死了：再调它的方法都是未定义行为（马上会 recreate）
        // 离开连接页去加载了：清掉"正在配对…"，真正的问题由 showSetup 负责说
        status.setText("");
        setBusy(false);
        loadingUrl = url;
        seedTokenCookie(url);
        cancelWatchdog();
        attemptFinished = false;
        webReady = false;
        pageDead = false;
        pageWasLive = false;
        managerOpen = false;
        resyncPending = false;
        // 只清"这次要重试的那个 URL"的失败记录；别的已判死的 URL 还要靠 failedUrl 挡它的迟到回调。
        // 原地重试这一种要留着标记：等它真的开始导航（onPageStarted）再解开，免得失败那次迟到的
        // onPageFinished 被当成重试的成功。
        if (url != null && url.equals(failedUrl) && !pageRetryInFlight) failedUrl = null;
        // 只在"等第一个响应"期间计时：onPageStarted 一到就撤掉看门狗，慢但在正常工作的服务不会被误判。
        watchdog = () -> candidateUnresponsive(url);
        handler.postDelayed(watchdog, timeoutMs);
        setup.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        showLoading(5); // 0% 的横向进度条画不出东西，先给 5% 让人看见"它开始动了"
        renderBall();
        web.loadUrl(url);
    }

    /**
     * 需要时把已存的 token 重新写进 cookie jar（系统清过 cookie，或者只是带着 token 重新冷启动）。
     * 只在 token 自己那条 origin 上用 —— 不跨地址发凭据：跨地址的话，一个恶意二维码里混进来的地址
     * 就能拿到凭据，或者在别的 origin 上覆盖掉用户本来能用的会话。
     */
    private void seedTokenCookie(String url) {
        String token = pendingToken;
        String tokenOrigin = pendingTokenOrigin;
        if (token == null) {
            Server server = active;
            if (server == null) return;
            token = server.token;
            tokenOrigin = server.tokenOrigin;
        }
        if (token == null || tokenOrigin == null) return;
        // 两边都按 base（scheme + authority）比：tokenOrigin 可能带着反代的路径前缀，而 cookie 是按 host 存的
        String origin = baseOf(normalize(url));
        String owner = baseOf(normalize(tokenOrigin));
        if (origin == null || owner == null || !origin.equals(owner)) return;
        CookieManager.getInstance().setCookie(origin, Redeem.cookieValue(origin, token, System.currentTimeMillis()));
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    /**
     * showSetup 会清空输入框；但"服务正在启动，几秒后再试"这类失败**还没花掉码**，
     * 把链接原样还回去，用户再点一次「连接」就是真正的重试，而不是重新去电脑上跑一遍 pair。
     */
    private void showSetupKeepingLink(String message) {
        showSetup(message);
        if (lastPairLink == null || input == null) return;
        input.setText(lastPairLink);
        input.setSelection(lastPairLink.length());
    }

    private void showSetup(String message) {
        cancelWatchdog();
        dropPendingPageRetry();
        cancelPageRetry();
        hideLoading();
        // 背后可能还压着一张活页面（配对失败/取消、token 过期都是管理页里的动作）：别把它判死，
        // 否则切回页面时 webReady 已经是 false，既补不了 resync 也看不出"页面还在"。
        // 用 active.origins 判断而不是 candidates()：配对期间 candidates() 是那几条新地址。
        // failedUrl/pageDead 是"这张页面已经报过错"的标记：别把 Chromium 的错误页当成活的。
        boolean liveBehind = webReady && failedUrl == null && !pageDead
                && web != null && !renderGone && isActiveOrigin(web.getUrl());
        // 回到连接页就是"这次不配了/不切了"：清掉进行中的尝试，晚到的回调别再把结果写进去。
        redeemGeneration++; // 在飞的那次兑换/探测就此作废（结果回来时要丢掉，别把用户拽回去）
        redeemInFlight = false;
        setBusy(false);
        probedDead = Collections.emptySet();
        loadingUrl = null;
        attemptOrigins = Collections.emptyList();
        attemptKey = "";
        pairingCode = "";
        attemptFinished = true;
        switchAt = 0; // 这次切台已经有结论（回连接页了）：别把后面某次加载算进这次耗时
        routeAttempt = false;
        failedUrl = null;
        webReady = liveBehind;
        managerOpen = false;
        pendingToken = null;
        pendingTokenOrigin = null;
        if (web != null) {
            // 停掉还在跑的加载，否则它稍后的 onPageFinished 会回头改状态（渲染进程都死了就别碰了）。
            // 背后那张活页面不算"还在跑的加载"：停掉它等于把用户正在看的页面掐成半截。
            if (!renderGone && !liveBehind) web.stopLoading();
            web.setVisibility(View.GONE);
        }
        setup.setVisibility(View.VISIBLE);
        input.setText("");
        status.setTextColor(getColor(R.color.shell_status)); // showSetup 的都是坏消息；中性消息走 say()
        status.setText(message == null ? "" : message);
        renderBall();
    }

    private void persistServers() {
        prefs.edit()
                .putString(KEY_SERVERS, encodeServers(servers))
                .putString(KEY_ACTIVE, active == null ? "" : active.key)
                .apply();
    }

    /**
     * 冷启动该回哪里：上次停的路由。返回该走哪条候选（-1 表示回根页面）。
     * 地址换了（隧道轮换、在别的网络）匹配不上就老实回根页面，别加载一个 key 对不上的路由
     * ——那会停在永远转圈的骨架页。
     */
    private int routeCandidate(Server server) {
        if (server == null || server.route == null) return -1;
        String target = baseOf(normalize(server.route));
        if (target == null) return -1;
        for (int i = 0; i < server.origins.size(); i++) {
            if (target.equals(baseOf(normalize(server.origins.get(i))))) return i;
        }
        return -1;
    }

    /**
     * 上游内嵌的 Web UI 固定把 `location.origin` 当服务器地址（`runtime/platform/web.ts` 的生产分支，
     * 构建模式由 `packages/cli/script/app-assets.ts` 写死成 `origin`），所以反代挂在 `/prefix` 下时：
     * 配对能成（我们就是按带前缀的 origin 兑换的），但页面自己会去打 `https://host/api/info`，前缀没了 → 进不去。
     * 配上了就说一句，别让用户以为能用。
     */
    private void warnAboutPathPrefix(List<String> origins) {
        for (String origin : origins) {
            String normalized = normalize(origin);
            if (normalized == null) continue;
            String base = baseOf(normalized);
            if (base == null || normalized.equals(base)) continue;
            Toast.makeText(this, "这个地址带路径前缀（" + pathOf(normalized)
                    + "）：OpenCode 内嵌的 Web UI 只认 origin，会把它丢掉，多半进不去。建议用根路径的地址。",
                    Toast.LENGTH_LONG).show();
            return;
        }
    }

    /**
     * 这个 URL 是不是还停在配对页上。要连 origin 的 path 一起比：反代把服务挂在 `/prefix` 下时真实路径是
     * `/prefix/auth/connect/<code>`，只看 {@link #pathOf} 会漏（于是失败页被当成配对成功、配对页被记成"上次的页面"）。
     */
    private boolean isConnectPage(String url) {
        String current = normalize(url);
        if (current == null) return false;
        for (String origin : candidates()) {
            String base = normalize(origin);
            if (base != null && current.startsWith(base + CONNECT_PATH)) return true;
        }
        return false;
    }

    /** 记下当前页面，下次冷启动直接回到这里（对齐 Web UI 自己在 PWA 里的 last-route）。 */
    void rememberRoute(String url) {
        if (active == null || url == null || !isTrustedOrigin(url)) return;
        if (isConnectPage(url)) return; // 配对页不算"上次的页面"
        if (url.equals(active.route)) return;
        active.route = url;
        persistServers();
    }

    /** 只信当前这台服务器（正在配对就是配对的那几条）：换服务器是显式动作，别让页面自己跨过去。 */
    private boolean isTrustedOrigin(String value) {
        String base = baseOf(normalize(value));
        if (base == null) return false;
        for (String origin : candidates()) {
            if (base.equals(baseOf(normalize(origin)))) return true;
        }
        return false;
    }

    /** 这个 URL 是不是"当前这台"（active）的页面。配对期间 candidates() 会换成新地址，这个判断不看它。 */
    private boolean isActiveOrigin(String value) {
        if (active == null) return false;
        String base = baseOf(normalize(value));
        if (base == null) return false;
        for (String origin : active.origins) {
            if (base.equals(baseOf(normalize(origin)))) return true;
        }
        return false;
    }

    private boolean isCurrentCandidate(String url) {
        if (candidates().isEmpty()) return false;
        String loaded = normalize(url);
        String current = normalize(candidates().get(candidate));
        if (loaded == null || current == null) return false;
        return loaded.equals(current) || loaded.startsWith(current + "/");
    }

    // ---------------------------------------------------------------- 多台服务器

    /** 一台 OpenCode 机器：key 是它的标识（配对时优先用的那条地址），origins 是它的候选地址，route 是上次停的路由。 */
    static final class Server {
        final String key;
        final List<String> origins;
        String route; // 上次离开时 WebView 停的 URL；只在地址对得上时复用（routeFor）
        String token; // 配对兑换来的会话 token（等同密码）。用完就落盘，用于 cookie 丢了时重注
        String tokenOrigin; // token 是从哪条 origin 兑换出来的：只在同一条上使用，不跨地址发凭据
        String name; // 用户起的名字，对齐上游的 displayName（它也存在客户端本地，不往服务器写）
        int slot; // 自动编号（1、2、3…）：没有用户名字时列表里就显示「服务器 N」，别拿长地址当名字

        Server(String key, List<String> origins) {
            this(key, origins, null);
        }

        Server(String key, List<String> origins, String route) {
            this.key = key;
            this.origins = origins;
            this.route = route;
        }
    }

    static String encodeServers(List<Server> servers) {
        JSONArray array = new JSONArray();
        for (Server server : servers) {
            JSONObject object = new JSONObject();
            try {
                object.put("key", server.key);
                object.put("origins", new JSONArray(server.origins));
                if (server.route != null) object.put("route", server.route);
                if (server.slot > 0) object.put("slot", server.slot);
                if (server.name != null && !server.name.trim().isEmpty()) object.put("name", server.name.trim());
                if (server.token != null && server.tokenOrigin != null) {
                    object.put("token", server.token);
                    object.put("tokenOrigin", server.tokenOrigin);
                }
            } catch (Exception ignored) {
                continue;
            }
            array.put(object);
        }
        return array.toString();
    }

    static List<Server> decodeServers(String json) {
        List<Server> servers = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return servers;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String key = normalize(object.optString("key", ""));
                if (key == null) continue;
                List<String> origins = new ArrayList<>();
                JSONArray list = object.optJSONArray("origins");
                if (list != null) {
                    for (int j = 0; j < list.length(); j++) {
                        String origin = normalize(list.optString(j, ""));
                        if (origin != null && !origins.contains(origin)) origins.add(origin);
                    }
                }
                if (!origins.contains(key)) origins.add(0, key);
                String route = object.optString("route", null);
                if (route != null && !route.startsWith("http://") && !route.startsWith("https://")) route = null;
                Server server = new Server(key, origins, route);
                String name = object.optString("name", "").trim();
                if (!name.isEmpty()) server.name = name;
                server.slot = object.optInt("slot", 0);
                // token 只在形状合法且来源地址还在候选里时才认（旧格式/被改过的盘上数据直接丢掉）
                String token = object.optString("token", null);
                String tokenOrigin = normalize(object.optString("tokenOrigin", ""));
                if (Redeem.validToken(token) && tokenOrigin != null && origins.contains(tokenOrigin)) {
                    server.token = token;
                    server.tokenOrigin = tokenOrigin;
                }
                servers.add(server);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return servers;
    }

    static Server findServer(List<Server> servers, String key) {
        if (key == null) return null;
        for (Server server : servers) {
            if (server.key.equals(key)) return server;
        }
        return null;
    }

    /** 配对结果并进已有条目（地址有交集就认为同一台机器），否则新增一条并排在最前。 */
    static List<Server> mergeServer(List<Server> servers, String key, List<String> origins) {
        List<Server> result = new ArrayList<>(servers);
        Server existing = findServer(result, key);
        if (existing == null) {
            for (Server server : result) {
                if (!intersects(server.origins, origins)) continue;
                // 两边都有 https（隧道）地址却不重合 → 是两台机器。只按局域网地址的交集合并会把它们粘成一条
                // （同一个网段里 DHCP 把同一个 IP 分给两台电脑太常见了），删一条两台一起没了。
                if (conflictingTunnel(server.origins, origins)) continue;
                existing = server;
                break;
            }
        }
        List<String> merged = new ArrayList<>();
        if (existing != null) merged.addAll(existing.origins);
        for (String origin : origins) {
            if (!merged.contains(origin)) merged.add(origin);
        }
        if (existing != null) result.remove(existing);
        // 上次停的路由跟着已有那条走；新配对没有路由。名字和编号也要带过去（用户改过名，再配对一次不能丢）。
        // token 不在这里继承：一次配对要么带来新的 token，要么（页面老路）说明服务端刚给了新 cookie，
        // 旧的那份留不得 —— 见 onPageFinished 里怎么写的。
        Server fresh = new Server(existing == null ? key : existing.key, merged, existing == null ? null : existing.route);
        fresh.name = existing == null ? null : existing.name;
        fresh.slot = existing != null && existing.slot > 0 ? existing.slot : nextSlot(result);
        result.add(0, fresh);
        return result;
    }

    /** 自动编号：最小的没被占用的正整数（删掉了再配回来复用，编号不跳）。 */
    static int nextSlot(List<Server> servers) {
        for (int candidate = 1; candidate <= servers.size() + 1; candidate++) {
            boolean used = false;
            for (Server server : servers) {
                if (server.slot == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) return candidate;
        }
        return servers.size() + 1;
    }

    /** 老数据/直接写 prefs 的条目没有编号：按当前顺序补上（列表顺序稳定，编号就稳定）。 */
    static void assignMissingSlots(List<Server> servers) {
        for (Server server : servers) {
            if (server.slot <= 0) server.slot = nextSlot(servers);
        }
    }

    private static boolean intersects(List<String> a, List<String> b) {
        for (String value : a) {
            if (b.contains(value)) return true;
        }
        return false;
    }

    /** 两边都给出了 https（隧道）地址、却一条都不重合：不是同一台机器。 */
    private static boolean conflictingTunnel(List<String> a, List<String> b) {
        List<String> secureA = new ArrayList<>();
        List<String> secureB = new ArrayList<>();
        for (String value : a) {
            if (value.startsWith("https://")) secureA.add(value);
        }
        for (String value : b) {
            if (value.startsWith("https://")) secureB.add(value);
        }
        return !secureA.isEmpty() && !secureB.isEmpty() && !intersects(secureA, secureB);
    }

    /** 和 Web UI 的 serverName() 一致：用户起的名字优先，其次自动编号「服务器 N」，最后才退回地址。 */
    static String displayName(Server server) {
        if (server == null) return "";
        if (server.name != null && !server.name.trim().isEmpty()) return server.name.trim();
        if (server.slot > 0) return "服务器 " + server.slot;
        return shortAddress(nameOf(server.key)); // 老数据没编号时的兜底（正常路径到不了这儿）
    }

    /**
     * 默认名字 = 中段省略的 authority。扫出来的隧道地址动辄 48 字符（`525db95f9b7bcdca.dtbcc7idjdi3.opentunnel.xyz`），
     * 整条当名字列表就没法看了：随机前缀是"哪一台"、域名后缀是"什么隧道"，两头都留。
     * 局域网那种（`192.168.1.2:49374`，17 字符）原样不动。
     */
    static String shortAddress(String authority) {
        if (authority == null) return "";
        if (authority.length() <= 22) return authority;
        return authority.substring(0, 8) + "…" + authority.substring(authority.length() - 12);
    }

    /**
     * 换地址时状态行/Toast 里怎么说这一条：形态（隧道/局域网）比长地址更快让人认出是哪台机器。
     */
    private static String candidateLabel(String origin) {
        String base = baseOf(normalize(origin));
        if (base == null) return "下一条地址";
        String authority = base.substring(base.indexOf("://") + 3);
        return addressLabel(base) + " " + shortAddress(authority);
    }

    /**
     * 一条地址是什么来头 —— 二维码里通常混着隧道和局域网地址（`opencode pair` 与 `--remote` 的区别就在这儿），
     * 光看主机名分不出来。纯按地址形态推导，不上网问。
     */
    static String addressLabel(String origin) {
        String normalized = normalize(origin);
        if (normalized == null) return "地址";
        if (normalized.startsWith("https://")) return "隧道"; // 上游的隧道是 https；局域网那条是 http
        String authority = normalized.substring(normalized.indexOf("://") + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.startsWith("[")) { // IPv6 字面量
            String host = authority.substring(1, authority.indexOf(']') < 0 ? authority.length() : authority.indexOf(']'));
            String lower = host.toLowerCase(Locale.ROOT);
            if (lower.startsWith("fd") || lower.startsWith("fe80")) return "局域网";
            if (lower.equals("::1") || lower.startsWith("::1:")) return "本机";
            return "直连";
        }
        String host = authority.contains(":") ? authority.substring(0, authority.indexOf(':')) : authority;
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("127.")) return "本机";
        if (isPrivateIpv4(host)) return "局域网";
        return "直连"; // 加了 --url 直连公网/域名，或者别的部署
    }

    private static boolean isPrivateIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                octets[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException error) {
                return false;
            }
            if (octets[i] < 0 || octets[i] > 255) return false;
        }
        if (octets[0] == 10 || octets[0] == 127) return true;
        if (octets[0] == 192 && octets[1] == 168) return true;
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) return true;
        return octets[0] == 169 && octets[1] == 254;
    }

    /** 用户改名（空 = 恢复成地址）。名字只存壳这边，对齐上游把 displayName 存在客户端本地。 */
    void rename(String key, String name) {
        Server server = findServer(servers, key);
        if (server == null) return;
        String trimmed = sanitizeName(name);
        server.name = trimmed.isEmpty() ? null : trimmed;
        persistServers();
        renderServers();
        renderBall(); // 球的读屏文案里带着当前名字，改名后要跟着变
    }

    /**
     * 名字只进 UI（列表行、撤销条、读屏、对话框）：去掉控制字符和双向控制符（U+202E 之类能把整行显示成反的），
     * 空白压成一个，再截到 {@link #NAME_MAX} 个码位（不按 UTF-16 截，免得把 emoji 劈成半个）。
     */
    static String sanitizeName(String raw) {
        if (raw == null) return "";
        // 注意：Android 的 ICU 正则不认 Java 的 (?U)\p{Cntrl}（真机上直接 PatternSyntaxException），
        // 所以控制字符和空白都用显式码位写出来，别用 \p{...} 和 (?U)。
        String cleaned = raw
                // 不可见/方向类：零宽（200B–200F）、双向嵌入/覆盖（202A–202E）、双向隔离（2066–2069）、
                // 阿拉伯字母标记（061C）、词连接符（2060）、废弃格式符（206A–206F）、BOM（FEFF）。
                .replaceAll("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]", "")
                // C0（00–1F）+ C1（7F–9F）控制字符。
                .replaceAll("[\\u0000-\\u001F\\u007F-\\u009F]", " ")
                // 各种 Unicode 空白：普通空白、NBSP、Ogham、En/Em 空格区、行/段分隔、窄不换行、中文字宽空格。
                .replaceAll("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+", " ")
                .trim();
        if (cleaned.codePointCount(0, cleaned.length()) <= NAME_MAX) return cleaned;
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, NAME_MAX)).trim();
    }

    static String nameOf(String origin) {
        String normalized = normalize(origin);
        if (normalized == null) return origin == null ? "" : origin;
        String authority = normalized.substring(normalized.indexOf("://") + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        // 直接比整段 authority（不切冒号）：IPv6 字面量原样显示，反正 loopback 链接在配对阶段就被挡了。
        if (authority.equals("localhost") || authority.startsWith("localhost:")
                || authority.equals("127.0.0.1") || authority.startsWith("127.0.0.1:")) return "local";
        return authority;
    }

    /** 连接页上的服务器列表：点按切换，行尾 ⋮ 是改名/复制地址/删除（长按删除作为快捷方式保留）。 */
    private void renderServers() {
        if (serverList == null) return;
        serverList.removeAllViews();
        serverList.setVisibility(View.VISIBLE);
        if (servers.isEmpty()) {
            serverList.addView(text("还没有服务器 —— 扫一下码，或把链接粘进来。",
                    13, getColor(R.color.shell_text_muted), false, Gravity.CENTER), top(dp(4)));
            applyPageLayout();
            return;
        }
        // 小节标题 + 它自己的动作：「添加」/「收起」放在同一行右侧，展开时表单就在列表下面（不用跳页）。
        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setOrientation(LinearLayout.HORIZONTAL);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeader.addView(text("服务器", 13, getColor(R.color.shell_text_muted), false, Gravity.START),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView add = text(formExpanded ? "收起" : "＋ 添加", 13, getColor(R.color.shell_accent), false, Gravity.END);
        add.setBackgroundResource(selectableBackground());
        add.setPadding(dp(8), dp(4), dp(8), dp(4));
        add.setOnClickListener(v -> {
            formExpanded = !formExpanded;
            applyPageLayout();
            renderServers(); // 动作文字要在「＋ 添加」和「收起」之间换
        });
        sectionHeader.addView(add);
        LinearLayout.LayoutParams headerParams = row();
        headerParams.bottomMargin = dp(8);
        serverList.addView(sectionHeader, headerParams);
        for (Server server : servers) {
            boolean current = active != null && active.key.equals(server.key);
            // 整行是一张卡片：左边两行字（点按切过去），右边 ⋮（改名/复制地址/删除）
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setBackground(card(current));
            line.setPadding(dp(12), dp(4), dp(4), dp(4));

            MaterialButton open = new MaterialButton(this, null,
                    com.google.android.material.R.attr.borderlessButtonStyle);
            // 第一行：● 当前 / ○ 其他 + 名字 + 种类（一行放得下；地址那行才可能折行）
            // 当前那台不只靠颜色区分（实心点 + 卡片描边 + 名字本身），色觉障碍也分得出。
            open.setText(dotLabel(displayName(server) + " · " + addressLabel(server.key), current));
            open.setAllCaps(false);
            open.setGravity(Gravity.START);
            open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            open.setTextColor(getColor(R.color.shell_text));
            // borderless 按钮自带的 minHeight/内边距会让两行文字跟卡片对不齐：这里要的是
            // "可点的文字"，水波纹留着（点了有反馈），盒子贴紧。
            open.setMinHeight(0);
            open.setMinimumHeight(0);
            open.setInsetTop(0);
            open.setInsetBottom(0);
            open.setPadding(0, 0, 0, 0);
            open.setTag(server.key);
            open.setOnClickListener(v -> switchTo(server));
            open.setOnLongClickListener(v -> {
                forget(server);
                return true;
            });

            // 第二行 = 中段省略的短地址（48 字符的隧道地址整条放进来会把卡片撑成三行；
            // 省略只切中间、两端保留，完整地址在 ⋮ → 复制地址 里）+ 当前那台的「· 当前」。
            // 第一行那个 ● 是"一眼看出来的"，这行是"说出来的"（不只靠颜色）。
            TextView address = text(shortAddress(nameOf(server.key)) + (current ? " · 当前" : ""),
                    13, getColor(R.color.shell_text_muted), false, Gravity.START);
            address.setTag(ADDRESS_TAG + server.key); // 给测试一个稳定拿法（tag 只加在 TextView 上，不骗 serverButtons）

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.addView(open, row());
            column.addView(address, row());
            address.setOnClickListener(v -> switchTo(server)); // 地址那行不是死区：点它跟点名字一样切过去
            column.setOnClickListener(v -> switchTo(server));
            column.setOnLongClickListener(v -> {
                forget(server); // 长按整张卡片也能删（不用非得按到名字那一行）
                return true;
            });
            line.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            MaterialButton more = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialIconButtonStyle);
            more.setIconResource(R.drawable.ic_more_vert); // 真图标，不是拿"⋮"当文字
            more.setIconTint(ColorStateList.valueOf(getColor(R.color.shell_text_muted)));
            more.setContentDescription("更多操作：" + displayName(server));
            more.setOnClickListener(v -> showServerActions(server));
            line.addView(more);
            serverList.addView(line, top(dp(8)));
        }
        applyPageLayout();
    }

    /**
     * 行尾 ⋮：改名 / 复制地址 / 删除（删除走撤销，不做二次确认，见 docs/design.md）。
     *
     * <p>不用 `PopupMenu`：它贴着那一行弹、**点任何别处就消失**，而且通篇不显示"这是哪台"——
     * 真机上想删除时根本没法确认自己点的是哪一行（这条是用户反馈的"交互时有时候会突然消失"）。
     * 换成跟切换面板同一套的对话框：顶部写清楚在操作哪台（名字 + 短地址 + 是否当前），下面是三个动作。
     */
    private void showServerActions(Server server) {
        boolean current = active != null && active.key.equals(server.key);
        LinearLayout panel = new LinearLayout(this);
        panel.setId(R.id.actions_panel);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(padded(text(displayName(server), 14, getColor(R.color.shell_text), false, Gravity.START),
                20, 16, 20, 0), row());
        panel.addView(padded(text(shortAddress(nameOf(server.key)) + (current ? " · 当前" : ""), 13,
                getColor(R.color.shell_text_muted), false, Gravity.START), 20, 2, 20, 12), row());
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.shell_border_muted));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerParams.leftMargin = dp(20);
        dividerParams.rightMargin = dp(20);
        panel.addView(divider, dividerParams);
        panel.addView(actionRow("改名", R.color.shell_text, () -> promptRename(server)));
        panel.addView(actionRow("复制地址", R.color.shell_text, () -> copyAddress(server)));
        // 删除是破坏性的：颜色上标出来（撤销条还在下面兜着，所以不用二次确认）
        panel.addView(actionRow("删除", R.color.shell_status, () -> forget(server)));
        actions = edgeToEdge(new MaterialAlertDialogBuilder(this), panel).create();
        actions.show();
    }

    /**
     * 自绘面板/对话框要贴边（行自己的 padding 就是内边距），所以用带 spacing 的 setView 把系统那圈默认间距归零。
     * AppCompat 把这个重载标成 {@code @RestrictTo(LIBRARY_GROUP_PREFIX)}，但它没有等价替代（公开的 setView(view)
     * 会加回 Material 的默认留白）；集中在这一处抑制，视觉上依赖的只是"四个 0 = 去掉留白"这一条稳定行为。
     */
    @android.annotation.SuppressLint("RestrictedApi")
    private static AlertDialog.Builder edgeToEdge(MaterialAlertDialogBuilder builder, View content) {
        return builder.setView(content, 0, 0, 0, 0);
    }

    /** 动作面板里的一行。点完先关面板再干活（不然改名弹窗会叠在它上面）。 */
    private View actionRow(String label, int colorRes, Runnable action) {
        TextView row = padded(text(label, 14, getColor(colorRes), false, Gravity.START), 20, 12, 20, 12);
        row.setBackgroundResource(selectableBackground());
        row.setOnClickListener(v -> {
            if (actions != null) {
                actions.dismiss();
                actions = null;
            }
            action.run();
        });
        return row;
    }

    private void copyAddress(Server server) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("opencode server", server.key));
        Toast.makeText(this, "已复制地址", Toast.LENGTH_SHORT).show();
    }

    /** 服务器卡片底：当前那台描一圈 accent，其余描一像素 border。 */
    private GradientDrawable card(boolean current) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(getResources().getDimension(R.dimen.shell_card_radius));
        shape.setColor(getColor(R.color.shell_card));
        if (current) {
            // 1dp 就够：行首那个 ● 和「· 当前」已经说明问题了，2dp 全环在真机上是一根蓝线圈
            shape.setStroke(Math.max(1, dp(1)), getColor(R.color.shell_accent));
        } else {
            shape.setStroke(Math.max(1, dp(1)), getColor(R.color.shell_border));
        }
        return shape;
    }

    private void openExternally(Uri url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (ActivityNotFoundException error) {
            Log.w(TAG, "no app handles " + url);
        }
    }

    // ---------------------------------------------------------------- 解析

    static final class Pairing {
        final List<String> origins; // 已排序：https 在前
        final String code;
        final String reason; // 非空表示识别出来了但不能用，内容是给用户看的原因

        private Pairing(List<String> origins, String code, String reason) {
            this.origins = origins;
            this.code = code;
            this.reason = reason;
        }

        static Pairing usable(List<String> origins, String code) {
            return new Pairing(origins, code, null);
        }

        static Pairing unusable(String reason) {
            return new Pairing(Collections.<String>emptyList(), "", reason);
        }
    }

    /** 接受两种 payload：pair 打印的链接，或它的二维码内容 {"code":…,"urls":[…]}。无法识别时返回 null。 */
    static Pairing parse(String raw) {
        if (raw == null) return null;
        String value = raw.replace("\uFEFF", "").replace("\u200B", "").trim();
        if (value.startsWith("{")) return parsePayload(value);
        // 旧版链接把凭据放在 /connect#<credentials> 或 /connect?data=<credentials> 里（上游 pairing.ts 的
        // legacyPairingLink）：只识别形状给个说法，不去请求它，也不把 fragment 写进日志。
        // 这里不借 Uri（保持 parse 全家都是纯字符串处理，纯 JVM 测试才跑得起来）。
        int schemeEnd = value.indexOf("://");
        if (schemeEnd > 0) {
            String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) {
                String rest = value.substring(schemeEnd + 3);
                int split = rest.length();
                for (int i = 0; i < rest.length(); i++) {
                    char c = rest.charAt(i);
                    if (c == '?' || c == '#') {
                        split = i;
                        break;
                    }
                }
                String tail = rest.substring(split);
                boolean legacy = rest.substring(0, split).endsWith("/connect")
                        && (tail.startsWith("#") ? tail.length() > 1 : tail.contains("data="));
                if (legacy) {
                    return Pairing.unusable("这是旧版格式的配对链接，请在你的电脑上重新运行 opencode pair 生成新的链接或二维码。");
                }
            }
        }
        return parseLink(value);
    }

    private static Pairing parsePayload(String payload) {
        String code;
        List<String> urls = new ArrayList<>();
        try {
            JSONObject json = new JSONObject(payload);
            // Android 的 org.json 对 JSON null 会返回字符串 "null"，那能过 code 的正则，得单独挡掉。
            code = json.isNull("code") ? null : json.getString("code");
            JSONArray array = json.getJSONArray("urls");
            for (int i = 0; i < array.length(); i++) {
                String url = array.optString(i, "");
                if (url != null && !url.trim().isEmpty()) urls.add(url);
            }
        } catch (Exception ignored) {
            return null;
        }
        return build(urls, code);
    }

    private static Pairing parseLink(String link) {
        // 上游 pairingLink() 是拿 URL 解析后只认 pathname，带 query/fragment 的链接照样能配对
        // （聊天软件常给链接追加 ?utm=...），所以这里先把 query/fragment 截掉再找 /auth/connect/。
        // 截断在前也顺手挡掉 `.../redirect?to=https://real/auth/connect/code` 这种。
        int cut = link.length();
        for (int i = 0; i < link.length(); i++) {
            char c = link.charAt(i);
            if (c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        String clean = link.substring(0, cut);
        int at = clean.toLowerCase(Locale.ROOT).indexOf(CONNECT_PATH);
        if (at < 0) return null;
        // code 必须是路径最后一整段（build 的正则会挡掉带 / 或为空的情况）。
        return build(Collections.singletonList(clean.substring(0, at)), clean.substring(at + CONNECT_PATH.length()));
    }

    static Pairing build(List<String> urls, String code) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{1,256}")) return null;

        List<String> secure = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        List<String> loopback = new ArrayList<>();
        for (String url : urls) {
            String origin = normalize(url);
            if (origin == null || secure.contains(origin) || plain.contains(origin) || loopback.contains(origin))
                continue;
            if (isLoopback(origin)) loopback.add(origin);
            else if (origin.startsWith("https://")) secure.add(origin);
            else plain.add(origin);
        }

        if (secure.isEmpty() && plain.isEmpty()) {
            if (loopback.isEmpty()) return null;
            // opencode pair 不带 --remote、hostname 又是默认的 localhost 时，打印出来的第一条链接就是这个。
            return Pairing.unusable("这是电脑本机的地址（" + loopback.get(0) + "），手机连不上。\n"
                    + "用同一条消息里的 https 隧道链接，或者先 opencode service set hostname 0.0.0.0 再 pair。");
        }

        secure.addAll(plain); // 优先 https（默认配置下二维码里只有隧道地址），连不上再退回局域网 http
        return Pairing.usable(secure, code);
    }

    /** 规范成 scheme://authority[/path]：scheme/host 小写、去掉 query/fragment 和结尾的 /，取不到返回 null。 */
    static String normalize(String input) {
        if (input == null) return null;
        String value = input.trim();
        int schemeEnd = value.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String rest = value.substring(schemeEnd + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        rest = rest.substring(0, end);
        while (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        if (rest.isEmpty()) return null;
        int slash = rest.indexOf('/');
        String authority = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
        String path = slash < 0 ? "" : rest.substring(slash);
        if (authority.isEmpty()) return null;
        // Chromium 序列化 URL 时会丢掉默认端口：配对写的是 https://host:443，回调里却是 https://host。
        // 不归一的话候选地址永远匹配不上（回调全被当成陈旧/外链，配对必超时）。
        String defaultPort = scheme.equals("https") ? ":443" : ":80";
        if (authority.endsWith(defaultPort)) authority = authority.substring(0, authority.length() - defaultPort.length());
        if (authority.isEmpty()) return null;
        return scheme + "://" + authority + path;
    }

    /** scheme://authority，拿来比"是不是同一个站"。 */
    static String baseOf(String normalizedOrigin) {
        if (normalizedOrigin == null) return null;
        int start = normalizedOrigin.indexOf("://");
        if (start < 0) return null;
        int slash = normalizedOrigin.indexOf('/', start + 3);
        return slash < 0 ? normalizedOrigin : normalizedOrigin.substring(0, slash);
    }

    /** URL 的 path（不含 query/fragment），取不到返回 null。 */
    static String pathOf(String url) {
        if (url == null) return null;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int start = url.indexOf('/', schemeEnd + 3);
        if (start < 0) return "/";
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return url.substring(start, end);
    }

    /** 手机上访问这些地址只会打到手机自己，永远连不上电脑。 */
    static boolean isLoopback(String origin) {
        String normalized = normalize(origin);
        if (normalized == null) return false;
        String authority = normalized.substring(normalized.indexOf("://") + 3);
        int slash = authority.indexOf('/');
        if (slash >= 0) authority = authority.substring(0, slash);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);

        String host;
        if (authority.startsWith("[")) {
            // IPv6 字面量只认括号里的内容，且括号后只能是 :端口 或结束；别让 http://[::1].evil 混过去。
            int close = authority.indexOf(']');
            if (close < 0) return false;
            String after = authority.substring(close + 1);
            if (!after.isEmpty() && !after.startsWith(":")) return false;
            host = authority.substring(1, close);
        } else {
            int colon = authority.indexOf(':');
            host = colon < 0 ? authority : authority.substring(0, colon);
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);

        return host.equals("localhost")
                || host.endsWith(".localhost")
                || host.startsWith("127.")
                || host.equals("0.0.0.0")
                || host.equals("::1")
                || host.equals("0:0:0:0:0:0:0:1")
                || host.startsWith("::ffff:127.");
    }

    // ---------------------------------------------------------------- 扫码

    private void scan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setCaptureActivity(ScannerActivity.class); // 自己那个 fullSensor 的版本，不然是横屏
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("扫描 opencode pair 输出的二维码");
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback != null) {
                ValueCallback<Uri[]> callback = fileCallback;
                fileCallback = null;
                // 无论结果怎么解析，回调都必须结掉，否则页面的 <input type=file> 永远等在那里。
                Uri[] picked = null;
                if (resultCode == RESULT_OK) {
                    try {
                        picked = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    } catch (Exception error) {
                        Log.w(TAG, "file chooser result 解析失败", error);
                    }
                }
                callback.onReceiveValue(picked);
            }
            return;
        }
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            if (result.getContents() != null) {
                // 从悬浮球扫码时人还在 Web UI 里：先回连接页，配对进度和「取消」才看得见；
                // 背后那张活页面由 showSetup 保下来（webReady 不丢，取消配对还能回来）。
                if (setup == null || setup.getVisibility() != View.VISIBLE) showSetup(null);
                connect(result.getContents());
            } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                // 库自己的权限对话框是英文，拒掉以后又没有回调：什么都不说，用户以为按钮坏了。
                Toast.makeText(this, "没有相机权限：可以在系统设置里打开，或者把链接直接粘进来。",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------------------------------------------------------- 视图

    static boolean needsCamera(String[] resources) {
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return true;
        }
        return false;
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled") // 整个 App 就是给可信 origin 跑官方 Web UI 的
    private WebView buildWebView() {
        WebView view = new WebView(this);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // Web UI 的语音等要用媒体，别等用户手势；也顺手让提示音能播。
        settings.setMediaPlaybackRequiresUserGesture(false);
        // target=_blank / window.open 默认会被 WebView 丢掉，开了这个才有 onCreateWindow 可接。
        settings.setSupportMultipleWindows(true);
        // Web UI 的 OAuth 是"先 await 拿授权地址、再 window.open"，那时用户手势早已过期；
        // 不开这个会被 WebView 静默拦掉。壳的 onCreateWindow 只会把它交给系统浏览器，不会在壳内开窗。
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        // 页面首帧之前 WebView 自己的底色是白的，深色 app 里那就是一闪白。给成壳底色。
        view.setBackgroundColor(getColor(R.color.shell_background));

        CookieManager.getInstance().setAcceptCookie(true);
        // 单 origin 的壳，不需要第三方 cookie。
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false);
        // 只有 debug 包才允许 chrome://inspect 抓这个 WebView。
        WebView.setWebContentsDebuggingEnabled(
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        // 页面里的 <a download> / Content-Disposition 下载：交给系统的 DownloadManager，并带上会话 cookie。
        view.setDownloadListener(this::onDownload);

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (view != web) { // 停着的页面不许要摄像头
                    request.deny();
                    return;
                }
                // Web UI 自己也有扫码入口，cookie 过期后在里面重新配对要用摄像头；但只给配过对的站。
                Uri origin = request.getOrigin();
                if (origin == null || !isTrustedOrigin(origin.getScheme() + "://" + origin.getAuthority())) {
                    request.deny();
                    return;
                }
                // Chromium 侧授权之外，app 自己也要持有 CAMERA（API 23+ 运行时权限），否则 getUserMedia
                // 照样开不了。壳自己的扫码页会顺手拿到它，但只用"粘贴链接"配对过的用户没有，这里按需申请。
                if (needsCamera(request.getResources())
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    if (pendingPermission != null) pendingPermission.deny(); // 别让上一次请求悬挂
                    pendingPermission = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CAMERA);
                    return;
                }
                request.grant(request.getResources());
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                if (v != web) return false; // 停着的页面不开窗
                // 不在壳里开新窗口：把目标地址丢给系统浏览器。
                WebView probe = new WebView(MainActivity.this);
                probes.add(probe);
                // 探针只用来拿一个 URL，不渲染、不跑脚本（外站脚本没有任何理由在这里执行）。
                probe.getSettings().setJavaScriptEnabled(false);
                probe.setWebViewClient(new WebViewClient() {
                    private boolean closed;
                    private boolean delegated;

                    private void close(WebView inner) {
                        if (closed) return; // destroy() 调两次不保证安全
                        closed = true;
                        // onDestroy 已经把它从集合里摘走并销毁了：别再 destroy 第二次
                        if (!probes.remove(inner)) return;
                        inner.destroy();
                    }

                    /** 把目标地址交给正确的地方（壳内 or 系统浏览器），只做一次。 */
                    private void delegate(WebView inner, Uri url) {
                        if (delegated || url == null || isDestroyed()) return;
                        delegated = true;
                        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
                        if (("http".equals(scheme) || "https".equals(scheme)) && isTrustedOrigin(url.toString())
                                && web != null && !renderGone) {
                            // 站内链接（比如"在新标签打开某个会话"）留在壳里跑，别丢出去。
                            web.loadUrl(url.toString());
                        } else {
                            openExternally(url);
                        }
                        close(inner);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView inner, WebResourceRequest request) {
                        delegate(inner, request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView inner, String url, Bitmap favicon) {
                        // 旧 WebView / 部分 302 不走 shouldOverrideUrlLoading：这里兜一道，别让地址丢掉。
                        if (url == null || "about:blank".equals(url)) return;
                        delegate(inner, Uri.parse(url));
                    }

                    @Override
                    public void onPageFinished(WebView inner, String url) {
                        if (url == null || "about:blank".equals(url)) {
                            // window.open() 之后 JS 再设 location：马上 destroy 会让那一步落空。
                            // 探针没 attach 到窗口，View.postDelayed 的 runnable 永远不会执行 —— 必须走 Activity 的 handler。
                            handler.postDelayed(() -> {
                                if (!isDestroyed()) close(inner);
                            }, 1500);
                            return;
                        }
                        delegate(inner, Uri.parse(url));
                    }

                    @Override
                    public boolean onRenderProcessGone(WebView inner, RenderProcessGoneDetail detail) {
                        // 不处理的话系统会直接杀掉整个 App 进程（探针共用同一个 renderer）。
                        close(inner);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(probe);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                super.onProgressChanged(v, newProgress);
                if (v != web) return; // 停着的页面在悄悄加载/收尾：进度条只代表当前页
                showLoading(newProgress);
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (v != web) return false;
                // Web UI 的附件和项目图标都是隐藏的 <input type=file>，没有这个回调，点上去毫无反应。
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (Exception error) {
                    // 设备上没有文件选择器（ATD 之类的精简镜像）：必须把回调结掉，否则后续请求统统被拒。
                    callback.onReceiveValue(null);
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                if (v != web) return true; // 停着的页面不许自己导航
                Uri url = request.getUrl();
                String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
                // 子框架（iframe）不接管：把外站 iframe 甩给系统浏览器，会把整页踢走，
                // 页面里嵌的文档/预览就再也打不开了。信任判定只管主框架。
                if (!request.isForMainFrame()) return false;
                // blob: 是 Web UI 的下载方式（设计上不做下载）：交给系统没有人接，点了像坏按钮，说清楚。
                if ("blob".equals(scheme)) {
                    Toast.makeText(MainActivity.this, "这个壳不能保存文件：要下载请在电脑上打开。",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                // 只有配对过的站在壳里跑，其余（外链、mailto、intent…）交给系统。
                if (("http".equals(scheme) || "https".equals(scheme)) && isTrustedOrigin(url.toString())) return false;
                openExternally(url);
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view != web) {
                    // 停着的那张崩了：只在以后切回来时别再复用它，不影响当前页
                    renderDead.add(view);
                    warm.values().remove(view);
                    handler.post(() -> destroyWeb(view));
                    return true;
                }
                onRenderGone(detail.didCrash());
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (v != web) return; // 停着的页面的事件不改当前页的状态
                if (url == null) return;
                if (url.equals(failedUrl)) {
                    // 已经判死的那次加载，它的迟到事件别动看门狗。唯一的例外是壳自己发起的
                    // "原地重试"：它开始导航了，这个 URL 后面的回调就都属于它。
                    if (!pageRetryInFlight) return;
                    pageRetryInFlight = false;
                    failedUrl = null;
                }
                // 302/POST 表单不会走 shouldOverrideUrlLoading，所以在这里兜一道：可信服务器（或局域网里的
                // 中间人）把页面跳到外站时，别让外站在壳里渲染（JS 全开、共享 cookie jar），交给系统浏览器。
                String scheme = Uri.parse(url).getScheme();
                scheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
                if (("http".equals(scheme) || "https".equals(scheme)) && !isTrustedOrigin(url)) {
                    v.stopLoading();
                    openExternally(Uri.parse(url));
                    if (webReady) {
                        // 已经在用 UI 了：别把人踢回连接页，浏览器会盖上来，回来还是原来那页。
                        Log.i(TAG, "外站链接交给系统浏览器：" + url);
                    } else {
                        showSetup("这个地址不是已配对的服务器，已交给系统浏览器打开。");
                    }
                    return;
                }
                // 第一个响应到了，看门狗可以撤了（后面的慢是正常加载）。
                if (isCurrentCandidate(url)) cancelWatchdog();
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (v != web) return;
                if (!request.isForMainFrame()) return;
                onLoadFailed(request.getUrl().toString(), String.valueOf(error.getDescription()),
                        error.getErrorCode());
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request, WebResourceResponse response) {
                if (v != web) return;
                if (!request.isForMainFrame()) return;
                onHttpError(request.getUrl().toString(), response.getStatusCode());
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                if (v != web) return;
                if (switchAt == 0 || switchVisibleAt != 0) return; // 不是切台，或者已经记过这一帧了
                if (!isCurrentCandidate(url)) return;
                switchVisibleAt = SystemClock.elapsedRealtime();
                Log.i(TAG, "切台：" + (switchWasWarm ? "热切" : "冷切") + "可见 "
                        + (switchVisibleAt - switchAt) + "ms → "
                        + (active == null ? "?" : active.key));
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (v != web) return;
                if (url != null && url.equals(failedUrl)) return; // 失败的加载晚到的事件，别当成成功
                if (!isCurrentCandidate(url)) return;
                if (attemptFinished) return; // 这一次已经有结论（失败提示已经显示），别拿晚到的回调覆盖
                cancelWatchdog();
                attemptFinished = true;
                routeAttempt = false;
                // 配对成功会 302 到 /；停在 /auth/connect/ 就是码失效、用过，或者这个地址压根不是 OpenCode。
                if (isConnectPage(url)) {
                    showSetup("配对链接已失效或已被使用（也可能这个地址不是 OpenCode 服务），请重新运行 opencode pair。");
                    return;
                }
                // 真进到 UI 了才算数：配对成功才把这台机器写进 list，失败的那次不会留下死条目。
                if (!attemptOrigins.isEmpty()) {
                    warnAboutPathPrefix(attemptOrigins);
                    // 重新配对过：这条地址/凭据可能变了，停着的那张页面不能再拿来复用
                    WebView stale = warm.remove(attemptKey);
                    if (stale != null) destroyWeb(stale);
                    servers = mergeServer(servers, attemptKey, attemptOrigins);
                    active = findServer(servers, attemptKey);
                    if (active == null && !servers.isEmpty()) active = servers.get(0);
                    if (active != null) {
                        // 这次配对带来了 token 就换成新的；走页面老路（没带 token）说明服务端刚给了这份 cookie，
                        // 存着的那份已经是旧的 —— 留着它只会在下次冷启动时把这份新的盖掉。
                        active.token = pendingToken;
                        active.tokenOrigin = pendingTokenOrigin;
                    }
                    pendingToken = null;
                    pendingTokenOrigin = null;
                    attemptOrigins = Collections.emptyList();
                    attemptKey = "";
                    pairingCode = "";
                    formExpanded = false;
                    renderServers();
                }
                persistServers();
                // 配对成功立刻把刚种下的 session cookie 落盘，别只依赖 onPause（被强杀/崩溃就丢了）。
                CookieManager.getInstance().flush();
                webReady = true; // 到这儿才算"壳里有能用的页面"
                authHintShown = false; // 新的一次成功加载：以后 401 还能再提醒
                pageRetries = 0; // 页面重新可用了：下一次断线还能再自动重试一次
                pageDead = false;
                pageWasLive = false;
                hideLoading();
                if (switchAt != 0) {
                    Log.i(TAG, "切台：" + (switchWasWarm ? "热切" : "冷切") + "就绪 "
                            + (SystemClock.elapsedRealtime() - switchAt) + "ms → "
                            + (active == null ? "?" : active.key));
                    switchAt = 0;
                    switchVisibleAt = 0;
                }
                // 第一次配对是在这里才拿到 active 的：不重画球的话，只有一个服务器时球永远不会出现。
                renderBall();
                // 这一台已经能用：把别的服务器的连接先热起来，下次切过去少付 DNS/TCP/TLS。
                preconnectOtherServers(false);
            }
        });
        return view;
    }

    private LinearLayout buildSetup() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = getResources().getDimensionPixelSize(R.dimen.shell_page_padding);
        box.setPadding(pad, pad, pad, pad);
        box.setBackgroundColor(getColor(R.color.shell_background));

        // 内容放在**内部**的滚动容器里：大字体 / 横屏 / 键盘弹起时还能滚到输入框。
        // 根仍然是 root.getChildAt(1) 那个 LinearLayout —— insets 的 padding 打在它身上（见 onCreate）。
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.setup_page);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)); // 撤销条要压在它下面常驻

        logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_mark); // 官方标识（圆角底板版，不是带安全区留白的自适应图标前景）
        logo.setContentDescription("OpenCode Client");
        content.addView(logo, new LinearLayout.LayoutParams(dp(96), dp(96)));

        pageTitle = text("OpenCode Client", 20, getColor(R.color.shell_text), true, Gravity.CENTER);
        content.addView(pageTitle, top(dp(12)));
        intro = text("在电脑上运行 opencode pair，然后扫码或把打印出来的链接粘进来。",
                14, getColor(R.color.shell_text_muted), false, Gravity.CENTER);
        content.addView(intro, top(dp(6)));

        // 配对这件事本身有三条最容易踩的：用哪条命令、码只能用一次、127.0.0.1 是电脑自己。
        // 直接写在输入框上面，别让人先去翻文档。
        pairHint = text("电脑上运行 opencode pair（本机）或 opencode pair --remote（隧道）。\n"
                        + "码 5 分钟内有效、只能用一次；带 127.0.0.1 的链接是电脑自己，别粘。",
                12, getColor(R.color.shell_text_muted), false, Gravity.CENTER);
        content.addView(pairHint, top(dp(8)));

        copyPairCommand = text("复制 opencode pair --remote", 12, getColor(R.color.shell_accent),
                false, Gravity.CENTER);
        copyPairCommand.setId(R.id.setup_copy_command);
        copyPairCommand.setPadding(dp(10), dp(6), dp(10), dp(6));
        copyPairCommand.setBackgroundResource(selectableBackground());
        copyPairCommand.setOnClickListener(v -> copyPairCommand());
        content.addView(copyPairCommand, top(dp(2)));

        // 输入区：带粘贴图标的 Material 输入框。下面那行 helper 会实时说明"这条链接指向哪儿"，
        // 链接本身有问题时改用 error（红字 + 描边），不用等按下连接才知道粘错了。
        TextInputLayout field = new TextInputLayout(this);
        field.setId(R.id.setup_field);
        field.setHint("配对链接");
        // 上游的输入框是"中性底 + 发丝环，聚焦时整圈变蓝"（text-input.css）。用 M3 的 outline 档、
        // 描边色自己给：filled 那套"灰坑 + 一条 2dp 蓝下划线"是模板脸，一眼就看得出没包过。
        field.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxStrokeColorStateList(inputStroke());
        field.setBoxCornerRadii(getResources().getDimension(R.dimen.shell_card_radius),
                getResources().getDimension(R.dimen.shell_card_radius),
                getResources().getDimension(R.dimen.shell_card_radius),
                getResources().getDimension(R.dimen.shell_card_radius));
        field.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        field.setEndIconDrawable(R.drawable.ic_paste);
        field.setEndIconContentDescription("粘贴剪贴板里的链接");
        field.setEndIconTintList(ColorStateList.valueOf(getColor(R.color.shell_text_muted)));
        field.setEndIconOnClickListener(v -> pasteLink());
        inputField = field;

        TextInputEditText url = new TextInputEditText(this);
        url.setId(R.id.setup_input);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHintTextColor(getColor(R.color.shell_text_muted));
        url.setTextColor(getColor(R.color.shell_text));
        url.setSingleLine(false);
        url.setMaxLines(3);
        url.setImeOptions(EditorInfo.IME_ACTION_DONE);
        url.setOnEditorActionListener((v, action, event) -> {
            connect(url.getText().toString());
            return true;
        });
        url.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            public void afterTextChanged(Editable s) {
                if (field.getError() != null) field.setError(null);
                field.setHelperText(describeLink(s == null ? "" : s.toString()));
            }
        });
        input = url;
        field.addView(url);
        content.addView(field, top(dp(24)));

        connectButton = new MaterialButton(this);
        connectButton.setText("连接");
        connectButton.setAllCaps(false);
        connectButton.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_primary)));
        connectButton.setTextColor(getColor(R.color.brand_on_primary));
        connectButton.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.shell_control_height));
        connectButton.setOnClickListener(v -> connect(input.getText().toString()));
        content.addView(connectButton, top(dp(20)));

        scannerButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        scannerButton.setText("扫描二维码");
        scannerButton.setAllCaps(false);
        // 次要按钮的文字走正文色（上游 `button.css` 的 secondary 就是中性底+中性字）；
        // 不显式给的话会吃到 colorPrimary，一屏两个蓝色次要按钮（蓝只留给"当前/链接"那种语义）
        scannerButton.setTextColor(getColor(R.color.shell_text));
        scannerButton.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.shell_control_height));
        scannerButton.setOnClickListener(v -> scan());
        content.addView(scannerButton, top(dp(8)));

        // 配对进行中的进度条：配对要真发网络请求，没反馈会让人以为点了没反应。
        // 用 INVISIBLE 占位，出现/消失时页面不跳。
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(getColor(R.color.shell_accent)));
        progress.setVisibility(View.INVISIBLE);
        content.addView(progress, top(dp(16)));

        // 配对要真发网络请求，"取消"得摆出来（返回键也能取消，但别指望用户知道）
        cancelButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancelButton.setText("取消");
        cancelButton.setAllCaps(false);
        cancelButton.setTextColor(getColor(R.color.shell_text));
        cancelButton.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.shell_control_height));
        cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> abortPairing());
        content.addView(cancelButton, top(dp(8)));

        status = text("", 13, getColor(R.color.shell_status), false, Gravity.CENTER);
        status.setId(R.id.setup_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); // 配对进度/失败原因得能播报
        content.addView(status, top(dp(12)));

        serverList = new LinearLayout(this);
        serverList.setId(R.id.setup_list);
        serverList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listParams = row();
        listParams.topMargin = dp(24);
        // 插在 logo/标题/说明之后、配对表单之前：日常进来先看到服务器列表，
        // 而不是先滚过一遍 logo + 说明 + 输入框（首屏那条空态文案还是在这儿）。
        content.addView(serverList, 3, listParams);

        // 删除后的"撤销"条：5 秒内可把条目放回原位置（连同"当前"）。
        // 不用 Material Snackbar：Robolectric 里断言不到它的内容，而且它要一个 anchor 视图。
        undoBar = new LinearLayout(this);
        undoBar.setId(R.id.setup_undo);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setBackground(card(false));
        undoBar.setElevation(dp(4)); // ≈ elevation-raised：贴着滚动内容时得看得出是浮在上面的
        undoBar.setPadding(dp(12), dp(6), dp(6), dp(6));
        undoBar.setVisibility(View.GONE);
        // 读屏要念出"可以撤销"（删除是破坏性操作，撤销窗口只有 5 秒）
        undoBar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        undoText = text("", 13, getColor(R.color.shell_text), false, Gravity.START);
        undoText.setMaxLines(2);
        undoText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        undoBar.addView(undoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton undoButton = new MaterialButton(this, null,
                com.google.android.material.R.attr.borderlessButtonStyle);
        undoButton.setText("撤销");
        undoButton.setAllCaps(false);
        undoButton.setContentDescription("撤销这次移除");
        undoButton.setOnClickListener(v -> undoRemove());
        undoBar.addView(undoButton);
        // 挂在 box 上、在滚动容器**外面**：列表一长，写在列表末尾的撤销条就滚到屏幕外了，
        // 5 秒撤销等于没有。
        box.addView(undoBar, top(dp(12)));
        return box;
    }

    /** 输入框下面那行随内容变的说明："将连接到 局域网 192.168.1.2:49374" / 为什么不能用。 */
    private String describeLink(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) return null;
        Pairing pairing = parse(trimmed);
        if (pairing == null) return "这不是 opencode pair 的链接或二维码";
        if (pairing.reason != null) return pairing.reason;
        String first = pairing.origins.get(0);
        return "将连接到 " + addressLabel(first) + " " + nameOf(first)
                + (pairing.origins.size() > 1 ? "（另有 " + (pairing.origins.size() - 1) + " 条备用地址）" : "");
    }

    /** 粘贴按钮：剪贴板里有能用的配对链接就直接连（省掉"填进去再按连接"）。 */
    void pasteLink() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
        CharSequence pasted = clip == null || clip.getItemCount() == 0 ? null : clip.getItemAt(0).coerceToText(this);
        String raw = pasted == null ? "" : pasted.toString().trim();
        if (raw.isEmpty()) {
            Toast.makeText(this, "剪贴板里没有内容", Toast.LENGTH_SHORT).show();
            return;
        }
        input.setText(raw);
        input.setSelection(raw.length());
        Pairing pairing = parse(raw);
        if (pairing != null && pairing.reason == null) connect(raw);
    }

    /** 配对进行中：亮进度条 + 亮出「取消」。四处状态切换都要经这里，否则会卡在"一直转"。 */
    void setBusy(boolean busy) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.INVISIBLE);
        if (cancelButton != null) cancelButton.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = row();
        params.topMargin = margin;
        return params;
    }

    /** 连接页上的一行文字。改这里之前先看 setBusy/status 那两处的顺序约束：状态行必须是 box 里
     *  最后一个"正好是 TextView"的直接子 view（测试靠它取状态文字）。 */
    private TextView text(CharSequence value, int sizeSp, int color, boolean bold, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setGravity(gravity);
        if (bold) view.setTypeface(view.getTypeface(), Typeface.BOLD);
        // 状态/说明常带 URL 和换行：中文挤在一起很难看，统一给一点行距（token 行高比 ≈ 1.3）。
        view.setLineSpacing(0f, 1.3f);
        return view;
    }

    private static FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams bottom(int margin) {
        LinearLayout.LayoutParams params = row();
        params.bottomMargin = margin;
        return params;
    }

    private static LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
