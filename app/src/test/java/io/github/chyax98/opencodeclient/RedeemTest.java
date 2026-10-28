package io.github.chyax98.opencodeclient;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 兑换客户端（{@link Redeem}）的测试：本地起一个真的 HTTP 服务当对手，真发请求。
 * 这里没有可注入的 seam —— 恰好用来钉住"线上到底发了什么请求头"，比如 Accept 里绝不能出现 text/html。
 *
 * <p>不用 {@code com.sun.net.httpserver}：那个包在单测的 classpath 上没有，自带一个够用的桩。
 */
public class RedeemTest {
    private static final String TOKEN = "1700000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";

    private StubServer server;
    private String origin;

    @Before
    public void startServer() throws Exception {
        server = new StubServer();
        origin = "http://127.0.0.1:" + server.port();
    }

    @After
    public void stopServer() {
        server.close();
    }

    @Test
    public void asksForTheApiBranchAndNeverSaysHtml() {
        server.reply = request -> StubServer.Response.json(200, "{\"token\":\"" + TOKEN + "\"}");

        Redeem.Result result = Redeem.attempt(origin, "abc123");

        assertEquals(Redeem.Status.PAIRED, result.status);
        assertEquals(TOKEN, result.token);
        StubServer.Request first = server.requests.get(0);
        assertEquals("GET", first.method);
        assertEquals("/auth/connect/abc123", first.path);
        // 上游按 Accept 里有没有 text/html 分流；一旦带上就回 302 + Set-Cookie，拿不到 token
        assertFalse("Accept 里不能出现 text/html：" + first.accept, String.valueOf(first.accept).contains("text/html"));
    }

    @Test
    public void aUsedOrExpiredCodeIsReportedAsExpired() {
        server.reply = request -> StubServer.Response.json(401, "{\"_tag\":\"UnauthorizedError\"}");

        assertEquals(Redeem.Status.EXPIRED, Redeem.attempt(origin, "abc123").status);
    }

    @Test
    public void a401FromSomethingElseIsNotBlamedOnTheCode() {
        // 中间代理挡下来的 401 不是"码失效"，不能这么告诉用户
        server.reply = request -> StubServer.Response.html(401, "<html>proxy auth</html>");

        assertEquals(Redeem.Status.UNREACHABLE, Redeem.attempt(origin, "abc123").status);
    }

    @Test
    public void aPlainWebPageIsNotOpencode() {
        // OpenCode 的 SPA 对未知路径也回 200 + index.html，所以状态码 200 不能算成功
        server.reply = request -> StubServer.Response.html(200, "<html><body>SPA</body></html>");

        assertEquals(Redeem.Status.NOT_OPENCODE, Redeem.attempt(origin, "abc123").status);
    }

    @Test
    public void aStartingServiceIsToldApart() {
        server.reply = request -> StubServer.Response.json(503, "{\"code\":\"service_starting\"}");

        assertEquals(Redeem.Status.STARTING, Redeem.attempt(origin, "abc123").status);
    }

    @Test
    public void aJsonBodyWithoutAUsableTokenIsNotASession() {
        server.reply = request -> StubServer.Response.json(200, "{\"token\":\"nope\"}");

        assertEquals(Redeem.Status.NOT_OPENCODE, Redeem.attempt(origin, "abc123").status);
    }

    @Test
    public void redirectsAreNotFollowed() {
        server.reply = request -> request.path.startsWith("/auth/connect/")
                ? StubServer.Response.redirect(302, "/elsewhere")
                : StubServer.Response.json(200, "{\"token\":\"" + TOKEN + "\"}");

        assertEquals(Redeem.Status.UNREACHABLE, Redeem.attempt(origin, "abc123").status);
        // 跟过去就会把请求（以及后续拿着 token 的动作）带到别的地址上
        assertEquals(1, server.requests.size());
    }

    @Test
    public void aDeadAddressIsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort(); // 拿到一个刚释放、肯定没人听的端口
        }

        Redeem.Result result = Redeem.attempt("http://127.0.0.1:" + closedPort, "abc123");

        assertEquals(Redeem.Status.UNREACHABLE, result.status);
    }

    @Test
    public void theProbeSendsTheTokenAsABasicPassword() {
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString(("opencode:" + TOKEN).getBytes(StandardCharsets.UTF_8));
        server.reply = request -> expected.equals(request.authorization)
                ? StubServer.Response.json(200, "{}")
                : StubServer.Response.json(401, "{\"_tag\":\"UnauthorizedError\",\"message\":\"Authentication required\"}");

        assertEquals(Redeem.Verify.OK, Redeem.tokenWorks(origin, TOKEN));
        assertEquals("/api/info", server.requests.get(0).path);
        // 401 是"这条地址明确拒了它"（那就别写 cookie），跟"验证那步网络不通"不是一回事
        assertEquals(Redeem.Verify.REJECTED,
                Redeem.tokenWorks(origin, "1700000000.othersignature9876543210zyxwvutsrq"));
    }

    @Test
    public void a401FromSomeOtherServiceIsNotARejection() {
        // 局域网 IP 被 DHCP 分给了 NAS/路由器这类带鉴权的机器：它们也回 401，但那不是 OpenCode。
        // 以前这里会把用户的 token 清掉（只是换了个网络），现在只认 OpenCode 的 UnauthorizedError。
        server.reply = request -> StubServer.Response.json(401, "{\"error\":\"unauthorized\"}");
        assertEquals(Redeem.Verify.UNREACHABLE, Redeem.tokenWorks(origin, TOKEN));

        server.reply = request -> StubServer.Response.json(403, "Forbidden");
        assertEquals(Redeem.Verify.UNREACHABLE, Redeem.tokenWorks(origin, TOKEN));

        server.reply = request -> StubServer.Response.json(403,
                "{\"_tag\":\"UnauthorizedError\",\"message\":\"Authentication required\"}");
        assertEquals(Redeem.Verify.REJECTED, Redeem.tokenWorks(origin, TOKEN));
    }

    @Test
    public void aProbeThatGetsA5xxIsNotARejection() {
        // 401/403 才是"这条地址明确拒了它"；5xx 说不准，别把用户赶回去重新生成一个码
        server.reply = request -> StubServer.Response.json(500, "{}");
        assertEquals(Redeem.Verify.UNREACHABLE, Redeem.tokenWorks(origin, TOKEN));
    }

    @Test
    public void aProbeThatCannotReachTheAddressIsNotARejection() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertEquals(Redeem.Verify.UNREACHABLE,
                Redeem.tokenWorks("http://127.0.0.1:" + closedPort, TOKEN));
    }

    @Test
    public void theCookieNameFollowsTheUpstreamPortRule() {
        // 上游用 Host 头里的端口命名 cookie；Host 不带默认端口（normalize 已经把默认端口剥掉了）
        assertEquals("opencode_session", Redeem.cookieName("https://tunnel.example"));
        assertEquals("opencode_session", Redeem.cookieName("https://host.example:443"));
        assertEquals("opencode_session", Redeem.cookieName("http://192.168.1.2:80"));
        assertEquals("opencode_session_49374", Redeem.cookieName("http://192.168.1.2:49374"));
        assertEquals("opencode_session_8443", Redeem.cookieName("https://tunnel.example:8443"));
        assertEquals("opencode_session", Redeem.cookieName("https://tunnel.example/prefix"));
        assertEquals("opencode_session_49374", Redeem.cookieName("https://tunnel.example:49374/prefix"));
        assertEquals("opencode_session_49374", Redeem.cookieName("http://[fd00::1]:49374"));
    }

    @Test
    public void theCookieCarriesTheAttributesTheServerWouldHaveWritten() {
        String value = Redeem.cookieValue("https://tunnel.example", TOKEN, 1_699_999_000_000L);

        assertTrue(value.startsWith("opencode_session=" + TOKEN));
        assertTrue(value.contains("path=/"));
        assertTrue(value.contains("HttpOnly")); // 不给页面 JS 读 token 的机会
        assertTrue(value.contains("SameSite=Lax"));
        assertTrue(value.contains("Max-Age=1000")); // token 自带过期时间减去当下
        assertTrue(value.contains("Secure"));
        // 局域网 http 上加 Secure 就等于发不出去
        assertFalse(Redeem.cookieValue("http://192.168.1.2:49374", TOKEN, 1_699_999_000_000L).contains("Secure"));
    }

    @Test
    public void anExpiredTokenGetsNoMaxAge() {
        // maxAge <= 0（token 已过期）时不写 Max-Age，别写出一个"立即过期"的怪 cookie
        assertTrue(Redeem.cookieValue("https://tunnel.example", TOKEN, 1_700_000_000_000L).startsWith("opencode_session=" + TOKEN));
        assertFalse(Redeem.cookieValue("https://tunnel.example", TOKEN, 1_700_000_000_000L).contains("Max-Age"));
    }

    @Test
    public void onlyWellShapedTokensAreAccepted() {
        assertTrue(Redeem.validToken(TOKEN));
        assertFalse(Redeem.validToken(null));
        assertFalse(Redeem.validToken(""));
        assertFalse(Redeem.validToken("1700000000"));
        assertFalse(Redeem.validToken("1700000000."));
        assertFalse(Redeem.validToken("1700000000.short"));
        assertFalse(Redeem.validToken("x.abcdefghijklmnopqrstuvwxyz012345"));
        // 形状校验挡的就是这种：直接拼进 Set-Cookie 会多出一条属性/第二条 cookie
        assertFalse(Redeem.validToken("1700000000.abcdefghijklmnopqrstuvwxyz012345\r\nSet-Cookie: evil=1"));
    }

    /**
     * 极简 HTTP 服务：单线程、一次处理一个请求、回完就关。故意做得笨，好在只服务于这几个用例。
     */
    @Test
    public void serviceStartsUpAndTheRetryAfterMakesItTryOnceMore() {
        int[] requests = {0};
        server.reply = request -> {
            requests[0]++;
            if (requests[0] == 1) {
                return StubServer.Response.retrying(503, "{\"code\":\"service_starting\"}", "1");
            }
            return StubServer.Response.json(200, "{\"token\":\"" + TOKEN + "\"}");
        };

        Redeem.Result result = Redeem.attempt(origin, "abc123");

        // 上游在服务没就绪时就把 503 返回了（码并没有被消费），所以等一下重发是安全的；也只重试这一次
        assertEquals(Redeem.Status.PAIRED, result.status);
        assertEquals(TOKEN, result.token);
        assertEquals(2, server.requests.size());
    }

    @Test
    public void aServiceThatFailedToStartIsNotReportedAsStillStarting() {
        server.reply = request -> StubServer.Response.json(503, "{\"code\":\"service_failed\"}");

        Redeem.Result result = Redeem.attempt(origin, "abc123");

        // service_failed 要去电脑上 opencode service restart，说成"再试一次"就是误导
        assertEquals(Redeem.Status.SERVICE_FAILED, result.status);
        assertEquals(1, server.requests.size()); // 这条不重试
    }

    @Test
    public void a503WithoutRetryAfterIsNotRetried() {
        server.reply = request -> StubServer.Response.json(503, "{\"code\":\"service_stopping\"}");

        Redeem.Result result = Redeem.attempt(origin, "abc123");

        assertEquals(Redeem.Status.STARTING, result.status);
        assertEquals(1, server.requests.size()); // 没给 retry-after 就不猜该等多久
    }

    @Test
    public void theTokenExpiryIsDecidedLocally() {
        long now = 1_800_000_000_000L; // 2027 年
        assertTrue(Redeem.expired("1700000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF", now)); // 2023
        assertFalse(Redeem.expired("2000000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF", now)); // 2033
        assertTrue("形状都不对就当它不能用", Redeem.expired("不是 token", now));
        assertTrue(Redeem.expired(null, now));
    }

    // ------------------------------------------------ 并行（错峰）兑换：Redeem.redeem

    /** 记录每条地址被问了几次，并给每条地址一个固定答复。 */
    private static final class Ladder {
        final List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        final Map<String, Redeem.Result> answers = new LinkedHashMap<>();
        final java.util.Set<String> rejected = new java.util.HashSet<>();

        java.util.function.BiFunction<String, String, Redeem.Result> attempt() {
            return (origin, code) -> {
                asked.add(origin);
                Redeem.Result answer = answers.get(origin);
                return answer == null ? Redeem.Result.of(Redeem.Status.UNREACHABLE, "no answer") : answer;
            };
        }

        java.util.function.BiFunction<String, String, Redeem.Verify> verify() {
            return (origin, token) -> rejected.contains(origin)
                    ? Redeem.Verify.REJECTED
                    : Redeem.Verify.OK;
        }
    }

    @Test
    public void aCodeIsNeverHandedToTheOtherAddressesWhenTheFirstOneAnswers() {
        Ladder ladder = new Ladder();
        ladder.answers.put("https://tunnel.example", Redeem.Result.paired(TOKEN));
        ladder.answers.put("http://192.168.1.2:49374", Redeem.Result.paired(TOKEN));

        Redeem.RedeemOutcome outcome = Redeem.redeem(
                java.util.Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"),
                "code123", ladder.attempt(), ladder.verify(), Redeem.HEDGE_MS);

        // 一次性码只发给了第一条：另一条根本没被问过（顺序兑换的暴露面，错峰不该扩大它）
        assertEquals(java.util.Collections.singletonList("https://tunnel.example"), ladder.asked);
        assertEquals("https://tunnel.example", outcome.origin);
        assertEquals(TOKEN, outcome.token);
        assertTrue(outcome.verified);
    }

    @Test
    public void aStalledFirstAddressIsBackedUpWithoutWaitingForItsFullTimeout() {
        Ladder ladder = new Ladder();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ladder.answers.put("https://dead.example", Redeem.Result.of(Redeem.Status.UNREACHABLE, "hangs"));
        ladder.answers.put("http://192.168.1.2:49374", Redeem.Result.paired(TOKEN));

        long started = System.currentTimeMillis();
        Redeem.RedeemOutcome outcome = Redeem.redeem(
                java.util.Arrays.asList("https://dead.example", "http://192.168.1.2:49374"),
                "code123", (origin, code) -> {
                    ladder.asked.add(origin);
                    if (origin.equals("https://dead.example")) {
                        try {
                            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return ladder.answers.get(origin);
                }, ladder.verify(), Redeem.HEDGE_MS);
        long elapsed = System.currentTimeMillis() - started;

        // 第一条卡住时，第二条在错峰到点后就被补发了；成功即可返回，不等第一条那 5 秒
        assertEquals("http://192.168.1.2:49374", outcome.origin);
        assertEquals(TOKEN, outcome.token);
        assertTrue("不该等第一条跑满超时（实测 " + elapsed + "ms）", elapsed < 3_000);
        release.countDown(); // 收尾：别让那条线程一直挂着
    }

    @Test
    public void a401FromOneAddressDoesNotHideATokenAnotherOneGot() {
        Ladder ladder = new Ladder();
        ladder.answers.put("https://first.example", Redeem.Result.of(Redeem.Status.EXPIRED, "used"));
        ladder.answers.put("https://second.example", Redeem.Result.paired(TOKEN));

        Redeem.RedeemOutcome outcome = Redeem.redeem(
                java.util.Arrays.asList("https://first.example", "https://second.example"),
                "code123", ladder.attempt(), ladder.verify(), Redeem.HEDGE_MS);

        // 竞速里输的那条会回 401（上游服务端对码是原子消费）：只要别处拿到了 token 就以 token 为准
        assertTrue(outcome.expired);
        assertEquals("https://second.example", outcome.origin);
        assertEquals(TOKEN, outcome.token);
        assertTrue(outcome.verified);
    }

    @Test
    public void aRejectedTokenFallsThroughToTheNextAddressAndKeepsTheFirstTokenAsTheStory() {
        Ladder ladder = new Ladder();
        ladder.answers.put("https://first.example", Redeem.Result.paired(TOKEN));
        ladder.rejected.add("https://first.example");
        ladder.answers.put("https://second.example", Redeem.Result.of(Redeem.Status.UNREACHABLE, "no"));

        Redeem.RedeemOutcome outcome = Redeem.redeem(
                java.util.Arrays.asList("https://first.example", "https://second.example"),
                "code123", ladder.attempt(), ladder.verify(), Redeem.HEDGE_MS);

        // 地址拒了 token（401/403）：换下一条地址；没有别人成功的话，仍然保留"拿到过形状合法 token"这条信息
        assertFalse(outcome.verified);
        assertEquals("https://first.example", outcome.origin);
        assertEquals(TOKEN, outcome.token);
        assertEquals(java.util.Arrays.asList("https://first.example", "https://second.example"), ladder.asked);
    }

    @Test
    public void everyAddressUnreachableIsReportedWithoutAToken() {
        Ladder ladder = new Ladder();
        ladder.answers.put("https://a.example", Redeem.Result.of(Redeem.Status.UNREACHABLE, "no route"));

        Redeem.RedeemOutcome outcome = Redeem.redeem(
                java.util.Collections.singletonList("https://a.example"),
                "code123", ladder.attempt(), ladder.verify(), Redeem.HEDGE_MS);

        assertNull(outcome.token);
        assertNull(outcome.origin);
        assertFalse(outcome.verified);
        assertEquals(Redeem.Status.UNREACHABLE, outcome.last.status);
    }

    private static final class StubServer implements Closeable {
        static final class Request {
            final String method;
            final String path;
            final String accept;
            final String authorization;

            Request(String method, String path, Map<String, String> headers) {
                this.method = method;
                this.path = path;
                this.accept = headers.get("accept");
                this.authorization = headers.get("authorization");
            }
        }

        static final class Response {
            final int status;
            final String contentType;
            final String body;
            final String location;
            final String retryAfter;

            private Response(int status, String contentType, String body, String location, String retryAfter) {
                this.status = status;
                this.contentType = contentType;
                this.body = body;
                this.location = location;
                this.retryAfter = retryAfter;
            }

            static Response json(int status, String body) {
                return new Response(status, "application/json", body, null, null);
            }

            static Response retrying(int status, String body, String retryAfter) {
                return new Response(status, "application/json", body, null, retryAfter);
            }

            static Response html(int status, String body) {
                return new Response(status, "text/html", body, null, null);
            }

            static Response redirect(int status, String location) {
                return new Response(status, "text/plain", "", location, null);
            }
        }

        interface Reply {
            Response handle(Request request) throws Exception;
        }

        final List<Request> requests = new ArrayList<>();
        Reply reply = request -> Response.html(200, "<html>default</html>");

        private final ServerSocket socket;
        private final Thread thread;

        StubServer() throws Exception {
            socket = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve, "stub-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private void serve() {
            while (!socket.isClosed()) {
                try (Socket client = socket.accept()) {
                    handle(client);
                } catch (Exception ignored) {
                    return; // 关服务时的正常退出路径
                }
            }
        }

        private void handle(Socket client) throws Exception {
            InputStream in = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String startLine = reader.readLine();
            if (startLine == null) return;
            String[] parts = startLine.split(" ");
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(),
                            line.substring(colon + 1).trim());
                }
            }
            requests.add(new Request(parts[0], parts[1], headers));

            Response response = reply.handle(new Request(parts[0], parts[1], headers));
            byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = client.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write("HTTP/1.1 " + response.status + " " + reason(response.status) + "\r\n");
            writer.write("Content-Type: " + response.contentType + "\r\n");
            writer.write("Content-Length: " + body.length + "\r\n");
            if (response.location != null) writer.write("Location: " + response.location + "\r\n");
            if (response.retryAfter != null) writer.write("Retry-After: " + response.retryAfter + "\r\n");
            writer.write("Connection: close\r\n\r\n");
            writer.flush();
            out.write(body);
            out.flush();
        }

        private static String reason(int status) {
            switch (status) {
                case 200: return "OK";
                case 302: return "Found";
                case 401: return "Unauthorized";
                case 503: return "Service Unavailable";
                default: return "Stub";
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关不上也无所谓，daemon 线程会随 JVM 结束
            }
        }
    }
}
