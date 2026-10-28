package io.github.chyax98.opencodeclient;

import java.util.Locale;

/**
 * 把"连不上"翻译成一句人话。
 *
 * <p>WebView 给的 {@code error.getDescription()} 是 `net::ERR_CONNECTION_TIMED_OUT` 这种东西，
 * 直接拼进状态行等于什么都没说：用户既不知道是谁的问题，也不知道下一步做什么。HTTP 错误同理。
 * 壳里所有失败出口都走这里，文案只有一份；纯 Java，JVM 上能直接测（{@code FailureTest}）。
 *
 * <p>错误码故意用字面值而不 import {@code WebViewClient}：这样测试不需要 Android 运行时。
 * 数值和上游常量一一对应，`FailureTest` 里有一条用例守住这个映射。
 */
final class Failure {

    // android.webkit.WebViewClient.ERROR_*
    static final int UNKNOWN = -1;
    static final int HOST_LOOKUP = -2;
    static final int AUTHENTICATION = -4;
    static final int PROXY_AUTHENTICATION = -5;
    static final int CONNECT = -6;
    static final int IO = -7;
    static final int TIMEOUT = -8;
    static final int REDIRECT_LOOP = -9;
    static final int UNSUPPORTED_SCHEME = -10;
    static final int FAILED_SSL_HANDSHAKE = -11;
    static final int BAD_URL = -12;
    static final int FILE = -13;
    static final int FILE_NOT_FOUND = -14;
    static final int TOO_MANY_REQUESTS = -15;
    static final int UNSAFE_RESOURCE = -16;

    /** 手机根本没有可用网络（不是"这台电脑连不上"）：先解决这个，试别的地址没意义。 */
    static final String OFFLINE = "手机没有网络。连上 Wi-Fi 或移动网络再试。";

    private static final String MSG_LOOKUP =
            "地址解析不了。检查地址有没有写错，或者换个网络（有的公司网络会拦隧道域名）。";
    private static final String MSG_REFUSED = "连不上这台电脑。确认它开着机、opencode 还在跑，隧道也没断。";
    private static final String MSG_TIMEOUT = "等不到响应。电脑可能休眠了，或者网络太慢，稍等一下再试。";
    private static final String MSG_UNREACHABLE = "网络到不了这个地址。检查隧道或 Wi-Fi 是不是掉了。";
    private static final String MSG_RESET = "连接中途断了。网络刚切换过的话，再试一次。";
    private static final String MSG_NETWORK_CHANGED = "网络刚切换过，再试一次。";
    private static final String MSG_TLS = "HTTPS 证书有问题（代理或自签证书）。检查中间有没有设备在替换证书。";
    private static final String MSG_BLOCKED = "系统或网络策略拦下了这次请求。换个网络再试。";

    private Failure() {
    }

    /**
     * 加载失败的说明。{@code online} 是调用方当场查到的网络状态：没网时不管错误码是什么，
     * 都先说"手机没网"，因为那才是用户能动手解决的那一层。
     */
    static String describe(int code, String description, boolean online) {
        if (!online) return OFFLINE;
        switch (code) {
            case HOST_LOOKUP:
                return MSG_LOOKUP;
            case CONNECT:
                return MSG_REFUSED;
            case TIMEOUT:
                return MSG_TIMEOUT;
            case IO:
                return MSG_RESET;
            case FAILED_SSL_HANDSHAKE:
                return MSG_TLS;
            case PROXY_AUTHENTICATION:
                return "网络代理要求登录，连不出去。检查 Wi-Fi 的代理设置。";
            case AUTHENTICATION:
                return "服务器拒绝了这次请求。回连接页重新运行 opencode pair。";
            case REDIRECT_LOOP:
                return "这个地址在来回跳转，多半配错了。检查配对链接里的地址。";
            case UNSUPPORTED_SCHEME:
            case BAD_URL:
            case FILE:
            case FILE_NOT_FOUND:
                return "这个地址打不开，检查配对链接是不是粘全了。";
            case TOO_MANY_REQUESTS:
                return "被服务器限流了，等几秒再试。";
            default:
                break;
        }
        // 有些内核只给英文描述、码是 UNKNOWN：按里面的 ERR_* 兜底。
        String byToken = describeToken(description);
        if (byToken != null) return byToken;
        String detail = description == null ? "" : description;
        if (detail.trim().isEmpty()) return "页面没加载成功。再试一次；还是不行就回连接页换一条地址。";
        // 真认不出来时留着原文：至少 logcat/截图里能看出是哪一层坏了，但先给一句人话。
        return "页面没加载成功。再试一次；还是不行就回连接页换一条地址。（" + detail + "）";
    }

    /** 从 `net::ERR_XXX` 这类描述里认出常见情况；认不出返回 null，交给调用方兜底。 */
    private static String describeToken(String detail) {
        if (detail == null || detail.isEmpty()) return null;
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("err_internet_disconnected")) return OFFLINE;
        if (lower.contains("err_network_access_denied")) return MSG_BLOCKED;
        if (lower.contains("err_name_not_resolved")) return MSG_LOOKUP;
        if (lower.contains("err_connection_refused")) return MSG_REFUSED;
        if (lower.contains("err_connection_timed_out") || lower.contains("err_timed_out")) return MSG_TIMEOUT;
        if (lower.contains("err_address_unreachable")) return MSG_UNREACHABLE;
        if (lower.contains("err_connection_reset") || lower.contains("err_connection_closed")
                || lower.contains("err_connection_aborted") || lower.contains("err_empty_response")) {
            return MSG_RESET;
        }
        if (lower.contains("err_network_changed")) return MSG_NETWORK_CHANGED;
        if (lower.contains("err_ssl") || lower.contains("err_cert")) return MSG_TLS;
        return null;
    }

    /** HTTP 状态码的说明（只用于主文档；子资源 4xx/5xx 是页面自己的事）。 */
    static String describeHttp(int status) {
        switch (status) {
            case 401:
            case 403:
                return "服务器不认这份登录了（" + status + "）。回连接页重新运行 opencode pair。";
            case 404:
                return "这条地址上没有 OpenCode（404）。确认端口和路径对不对。";
            case 429:
                return "服务器在限流（429），等几秒再试。";
            case 502:
            case 503:
            case 504:
                return "隧道通了，但后面的 opencode 服务没响应（" + status + "）。看看电脑上的 opencode 还在不在跑。";
            default:
                if (status >= 500) return "服务器出错了（" + status + "）。看看电脑上的 opencode 日志。";
                return "请求被拒绝（" + status + "）。检查地址和配对是否还有效。";
        }
    }
}
