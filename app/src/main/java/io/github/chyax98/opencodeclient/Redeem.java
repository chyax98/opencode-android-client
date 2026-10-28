package io.github.chyax98.opencodeclient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * 配对码兑换。
 *
 * 上游 {@code /auth/connect/<code>} 有两条分支，靠请求头 {@code Accept} 里有没有 {@code text/html} 分流
 * （{@code packages/server/src/handlers/server.ts}）：浏览器那条回 302 + Set-Cookie，
 * API 那条回 {@code {"token":"<expires>.<签名>"}}。这里走 API 那条 —— 自己拿到 token、自己写进 WebView 的
 * cookie jar，好处是不用赌 302/HTML 的中间环节，而且能靠状态码把几种失败分开：
 *
 * <ul>
 *   <li>{@code 200 application/json} → PAIRED（token 是 {@code expires.base64url} 的签名，格式固定）</li>
 *   <li>{@code 401 "UnauthorizedError"} → EXPIRED（码失效/已被使用）</li>
 *   <li>{@code 503 service_starting} → STARTING（服务没起完，等几秒重试即可）</li>
 *   <li>{@code 200 text/html} → NOT_OPENCODE（多半打到了别的站：OpenCode 的 SPA 对未知路径也是 index.html）</li>
 *   <li>其它 → UNREACHABLE（连不上、超时、4xx/5xx 非上述、3xx）</li>
 * </ul>
 */
final class Redeem {
    enum Status { PAIRED, EXPIRED, NOT_OPENCODE, STARTING, SERVICE_FAILED, UNREACHABLE }

    /** 用 token 自校验的结果：区分"这个地址明确拒了它"和"验证那一步网络不通"。 */
    enum Verify { OK, REJECTED, UNREACHABLE }

    /** 一次兑换尝试的汇总（后台线程算好，主线程据此决定写 cookie / 给提示 / 退回页面加载）。 */
    static final class RedeemOutcome {
        final String origin; // 拿到形状合法 token 的那条地址（可能被地址拒了，见 verified）
        final String token; // 仅当换到过形状合法的 token
        final Result last; // 最后一条有信息量的失败，用来给提示
        final boolean verified; // token 通过了 /api/info（或者只是那一步网络抖了，见 tokenWorks）
        final boolean expired; // 有地址回了"码失效"
        final boolean notOpencode; // 有地址回了 200 但不像 OpenCode

        RedeemOutcome(String origin, String token, Result last, boolean verified,
                      boolean expired, boolean notOpencode) {
            this.origin = origin;
            this.token = token;
            this.last = last;
            this.verified = verified;
            this.expired = expired;
            this.notOpencode = notOpencode;
        }
    }

    static final class Result {
        final Status status;
        final String token; // 仅 PAIRED
        final String detail; // 日志/提示用

        private Result(Status status, String token, String detail) {
            this.status = status;
            this.token = token;
            this.detail = detail;
        }

        static Result paired(String token) {
            return new Result(Status.PAIRED, token, "ok");
        }

        static Result of(Status status, String detail) {
            return new Result(status, null, detail);
        }
    }

    // 和上游 issueSession() 的 `${expires}.${base64url(签名)}` 同形。校验形状是为了不把任意字符串
    // 拼进 Set-Cookie（CR/LF 或 `; Domain=` 会变成第二条 cookie）。
    private static final Pattern TOKEN = Pattern.compile("^[0-9]{1,12}\\.[A-Za-z0-9_-]{20,256}$");
    private static final int TIMEOUT_MS = 5_000; // 对齐上游兑换用的 5 秒预算
    /**
     * 错峰兑换：第一条立刻发，隔这么久还没**结论性应答**才补发下一条（见 {@link #redeem}）。
     * 取 1 秒是照上游自己的尺子：{@code pairing.ts} 里"一秒还没答的地址就不再等它"。
     */
    static final long HEDGE_MS = 1_000;
    private static final int MAX_PARALLEL = 4; // 同时在飞的兑换上限（正常只有 2-3 条地址）
    private static final long POLL_MS = 200; // 等结果的分片，用来在错峰到点时醒来补发
    private static final int MAX_BODY = 64 * 1024;
    private static final String SESSION_COOKIE_PREFIX = "opencode_session";

    /** 一次兑换尝试。不抛异常：任何问题都变成 Status（调用方按 Status 决定提示/回退）。 */
    static Result attempt(String origin, String code) {
        return attempt(origin, code, true);
    }

    /**
     * @param retryAllowed 服务"正在启动/停止"时允许**再来一次**（上游那种 503 里码并没有被消费，
     *                     外层 {@code process.ts} 就返回了，所以等一下重发是安全的；且只重试这一次，
     *                     免得服务真起不来时把用户卡在这里）。
     */
    private static Result attempt(String origin, String code, boolean retryAllowed) {
        HttpURLConnection connection = null;
        try {
            // code 已经在 parse 里过过 [A-Za-z0-9_-]{1,256}，不需要再编码。
            connection = (HttpURLConnection) new URL(origin + CONNECT_PATH + code).openConnection();
            // 必须显式写：不能含 text/html（含了就翻成浏览器分支，拿不到 token）。
            connection.setRequestProperty("Accept", "*/*");
            // 3xx 一律当失败：跟到别处会把 token 张冠李戴，而且 HttpURLConnection 不处理 Set-Cookie。
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);

            int status = connection.getResponseCode();
            String contentType = connection.getContentType() == null
                    ? ""
                    : connection.getContentType().toLowerCase(Locale.ROOT);
            String body = read(connection, status);

            if (status == 401 && body.contains("UnauthorizedError")) return Result.of(Status.EXPIRED, body);
            if (status == 503) return startingOrFailed(connection, origin, code, body, retryAllowed);
            if (status != 200) return Result.of(Status.UNREACHABLE, status + " " + contentType + " " + body);
            if (!contentType.contains("json")) return Result.of(Status.NOT_OPENCODE, contentType + " " + body);

            String token = new JSONObject(body).optString("token", "");
            if (!validToken(token)) return Result.of(Status.NOT_OPENCODE, "token 形状不对：" + token.length() + " 字符");
            return Result.paired(token);
        } catch (Exception error) {
            return Result.of(Status.UNREACHABLE, String.valueOf(error));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 上游的 503 有两种（{@code packages/server/src/process.ts}）：后台服务起不来
     * （{@code service_failed}，得去电脑上 {@code opencode service restart}）和正在启动/停止
     * （{@code service_starting}/{@code service_stopping}，带 {@code retry-after}，等一两秒就行）。
     * 混成一句话会把"起不来"说成"再试一次"。
     */
    private static Result startingOrFailed(HttpURLConnection connection, String origin, String code,
                                           String body, boolean retryAllowed) {
        if (body.contains("service_failed")) return Result.of(Status.SERVICE_FAILED, "503 " + body);
        long retryAfter = retryAfterMillis(connection);
        if (retryAllowed && retryAfter > 0) {
            try {
                Thread.sleep(retryAfter);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return Result.of(Status.STARTING, "503 " + body + "（等待重试时被打断）");
            }
            return attempt(origin, code, false);
        }
        return Result.of(Status.STARTING, "503 " + body);
    }

    /** {@code retry-after} 是秒；只在 1-2 秒这种"等一下就好"的量级上重试。 */
    private static long retryAfterMillis(HttpURLConnection connection) {
        String header = connection.getHeaderField("retry-after");
        if (header == null) return 0;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 && seconds <= 2 ? seconds * 1000L : 0;
        } catch (NumberFormatException error) {
            return 0; // 也可能是 HTTP-date 形式：不猜
        }
    }

    /**
     * 在候选地址上兑换：**并行但错峰**。
     *
     * <p>上游客户端是 {@code Promise.any(link.urls.map(url => server.connect(...)))} —— 所有地址同时拿到码。
     * 壳不那样做：码是**一次性凭据**（上游服务端 {@code pairing.consume} 用 {@code Cache.invalidateWhen}
     * 原子消费，容量 1000、TTL 5 分钟，所以多条地址同时兑换只有一个能成功，另一个拿 401），
     * 把它发给一条用不着的地址就等于把它交给那条地址的运营者。于是取中间：
     *
     * <ul>
     *   <li>第一条地址立刻发；</li>
     *   <li>隔 {@code hedgeMs} 还没结论才补发下一条（所以"第一条是死地址"的代价从一次完整的 5 秒变成 ~1 秒）；
     *       反过来，401/503/不是 OpenCode 这类**明确应答**会立刻推进下一条，不占这份预算；</li>
     *   <li>一旦有地址换到**通过验证**的 token 就不再发后面的，剩下在飞的也掐掉。</li>
     * </ul>
     *
     * <p>正常情况（第一条地址是活的）下，码只会发给第一条地址 —— 这一点和顺序兑换一样。
     *
     * <p>反复打同一个码不会得到第二个会话，只会得到 401；那不算"码失效"的误报，因为只要别处
     * 换到了 token，{@link RedeemOutcome#token} 就非空，调用方以它为准。
     *
     * <p>不抛异常：任何结果都汇总成 {@link RedeemOutcome}。
     */
    static RedeemOutcome redeem(List<String> origins, String code,
                                BiFunction<String, String, Result> attempt,
                                BiFunction<String, String, Verify> verify,
                                long hedgeMs) {
        Result best = null;
        String winner = null;
        String token = null;
        boolean verified = false;
        boolean expired = false;
        boolean notOpencode = false;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(origins.size(), MAX_PARALLEL));
        try {
            CompletionService<Attempt> ladder = new ExecutorCompletionService<>(pool);
            int next = 0;
            int outstanding = 0;
            long hedgeAt = 0;
            // 拿到通过验证的 token 就结束循环：不再发新的，也不再等还在飞的（见方法注释）
            while (!verified && (next < origins.size() || outstanding > 0)) {
                if (next < origins.size() && (outstanding == 0 || System.currentTimeMillis() >= hedgeAt)) {
                    final String origin = origins.get(next++);
                    ladder.submit(() -> new Attempt(origin, attempt.apply(origin, code)));
                    outstanding++;
                    hedgeAt = System.currentTimeMillis() + hedgeMs;
                    continue;
                }
                Future<Attempt> future = ladder.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (future == null) continue; // 只是错峰到点了，下一轮补发
                outstanding--;
                Attempt done = future.get();
                Result result = done.result;
                if (best == null || result.status == Status.PAIRED) best = result;
                if (result.status == Status.EXPIRED) expired = true;
                if (result.status == Status.NOT_OPENCODE) notOpencode = true;
                if (result.status != Status.PAIRED) continue;
                if (winner == null) {
                    winner = done.origin; // 形状合法先记住：万一都验不过，也能给用户一个准确的说法
                    token = result.token;
                }
                // 再用它当密码打一次需要鉴权的接口确认能用。只在这条 origin 上做 ——
                // 拿 token 去别的地址探测等于把凭据送给对方（见 docs/design.md 的信任边界）。
                if (verify.apply(done.origin, result.token) != Verify.REJECTED) {
                    // OK 就是验过了；UNREACHABLE 是"兑换本身 200 + token 形状合法，验证那一步网络抖了"，
                    // 先接受（页面会用真实请求说话），别因为这个把用户赶回去重新生成一次码。
                    winner = done.origin;
                    token = result.token;
                    verified = true;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException ignored) {
            // attempt/verify 自己不抛异常；真出了别的意外也别让配对卡死在这里
        } finally {
            pool.shutdownNow(); // 已经成功了（或没得发了）：剩下在飞的不用等，顺手把它们从地址那边撤掉
        }
        return new RedeemOutcome(winner, token, best, verified, expired, notOpencode);
    }

    /** 一条地址的一次尝试（带上是谁回的，汇总时才说得清）。 */
    private static final class Attempt {
        final String origin;
        final Result result;

        Attempt(String origin, Result result) {
            this.origin = origin;
            this.result = result;
        }
    }

    /** token 必须是 {@code expires.签名} 的形状，才允许写出去。 */
    static boolean validToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /**
     * token 第一段就是它自己的过期时间（秒，上游 {@code auth.ts} 的 TTL 是 30 天）。
     * 本地就能判，不用请求 —— 冷启动时用它挡住"已经过期还往 cookie 里种"的那种情况。
     */
    static boolean expired(String token, long nowMillis) {
        if (!validToken(token)) return true;
        return Long.parseLong(token.split("\\.")[0]) * 1000L <= nowMillis;
    }

    /**
     * 用 token 当密码打一次 {@code GET /api/info}（该接口需要鉴权）。三态是为了区分
     * "这条地址明确拒了它"（那就别写 cookie）和"验证这一步网络不通"（兑换本身是好的，先接受）。
     * 只在兑换成功的那条 origin 上做 —— 拿 token 去别的地址探测等于把凭据送给对方。
     */
    static Verify tokenWorks(String origin, String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin + "/api/info").openConnection();
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Authorization", basic(token));
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status == 200) return Verify.OK;
            if (status == 401 || status == 403) {
                // 跟 attempt 用同一条判据：只有 OpenCode 自己的 UnauthorizedError 才算"明确拒了"。
                // 局域网 IP 被 DHCP 分给另一台带鉴权的机器（NAS/路由器）时也会回 401，
                // 那种情况把凭据清掉是错的（用户只是换了网络）。
                return read(connection, status).contains("UnauthorizedError") ? Verify.REJECTED : Verify.UNREACHABLE;
            }
            return Verify.UNREACHABLE; // 5xx 之类：说不准，交给页面用真实请求去暴露
        } catch (Exception error) {
            return Verify.UNREACHABLE;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** 上游 auth.ts 的 sessionCookieName()：Host 里带端口时是 {@code opencode_session_<port>}。 */
    static String cookieName(String origin) {
        String authority = authorityOf(origin);
        if (authority == null) return SESSION_COOKIE_PREFIX;
        int port = portOf(authority);
        // 默认端口不会出现在 Host 头里，服务端那边也不带后缀
        if (port < 0 || port == (origin.startsWith("https://") ? 443 : 80)) return SESSION_COOKIE_PREFIX;
        return SESSION_COOKIE_PREFIX + "_" + port;
    }

    /**
     * 自己拼 Set-Cookie：属性与上游写的一致（host-only、path=/、HttpOnly、SameSite=Lax、到 token 自带过期时间），
     * 只在 https origin 上加 Secure（局域网 http 加了就发不出去）。
     */
    static String cookieValue(String origin, String token, long nowMillis) {
        long maxAge = Long.parseLong(token.split("\\.")[0]) - nowMillis / 1000;
        StringBuilder value = new StringBuilder()
                .append(cookieName(origin)).append('=').append(token)
                .append("; path=/; HttpOnly; SameSite=Lax");
        if (maxAge > 0) value.append("; Max-Age=").append(maxAge);
        if (origin.startsWith("https://")) value.append("; Secure");
        return value.toString();
    }

    /** 上游用 Host 头解析端口，而 Host 不带默认端口 —— 我们的 origin 已经由 normalize 剥过默认端口。 */
    static int portOf(String authority) {
        String host = authority;
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) return -1;
            String rest = host.substring(close + 1);
            return rest.startsWith(":") ? parsePort(rest.substring(1)) : -1;
        }
        String[] parts = host.split(":");
        return parts.length > 1 ? parsePort(parts[parts.length - 1]) : -1;
    }

    static String authorityOf(String origin) {
        if (origin == null) return null;
        int scheme = origin.indexOf("://");
        if (scheme < 0) return null;
        String rest = origin.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String basic(String token) {
        // java.util.Base64（API 26 起可用）：不依赖 android.util.Base64，纯 JVM 测试里也能跑
        byte[] credentials = ("opencode:" + token).getBytes(StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(credentials);
    }

    /** 4xx/5xx 的 body 在 error stream 上，直接读 input stream 会抛 IOException。 */
    private static String read(HttpURLConnection connection, int status) {
        InputStream stream = null;
        try {
            stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            while (total < MAX_BODY) {
                int read = stream.read(buffer, 0, Math.min(buffer.length, MAX_BODY - total));
                if (read < 0) break;
                out.write(buffer, 0, read);
                total += read;
            }
            return out.toString("UTF-8");
        } catch (Exception error) {
            return "";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // 读失败时的收尾，无所谓
                }
            }
        }
    }

    static final String CONNECT_PATH = "/auth/connect/";
}
