package io.github.chyax98.opencodeclient;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 候选地址探测：**只回答"这个地址这会儿在不在"**。
 *
 * <p>用途不是"先探完再加载"（那会给每次冷启动加一次往返），而是**加载卡住时的快速换代**：
 * WebView 那边要等 20 秒看门狗才发现"这条地址一个响应都没有"，而一次裸 TCP connect 两秒内就有答案。
 *
 * <p>只做 TCP connect：不发 HTTP、不带凭据。上游（{@code packages/app/src/servers/connect/pairing.ts}
 * 的 {@code preferFirst}）是拿 token 当 Basic 密码去健康检查，而壳的凭据只允许用在自己被兑换出来的那条
 * origin 上（见 docs/design.md）—— 拿它去别的地址探测等于把凭据送出去。裸 connect 也躲开了证书、
 * 重定向、代理那一堆麻烦。
 *
 * <p>注意：别拿 {@code /auth/connect/...} 去探，那个路径会把**一次性**配对码花掉。
 */
final class Probe {
    /** 一次探测的两种结论。没探完的地址不在结果里 = 未知，调用方一律当"不是 DEAD"处理。 */
    enum Liveness { ALIVE, DEAD }

    /** 探测总预算：黑洞地址（SYN 被丢掉）不该拖慢换代。 */
    static final long DEADLINE_MS = 2_600;
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    /**
     * 连上就算活着。连不上记 DEAD —— 但"要不要因此丢掉当前正在加载的地址"由调用方决定：
     * 一条地址只是**慢**（两秒都还没连上）和它**不在**，从这一侧分不出来，所以主流程只在
     * "另一条活着、且当前这条不带着我们的会话"时才提前换代（见 MainActivity 的 applyProbeVerdicts）。
     */
    static Liveness probe(String origin) {
        Socket socket = new Socket();
        try {
            InetSocketAddress address = addressOf(origin);
            if (address == null) return Liveness.DEAD;
            socket.connect(address, CONNECT_TIMEOUT_MS);
            return Liveness.ALIVE;
        } catch (Exception error) {
            return Liveness.DEAD;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 关不上也无所谓：探测用的 socket 随线程结束
            }
        }
    }

    /** 并行探一遍，最多等 {@link #DEADLINE_MS}；没赶上的地址干脆不出现在结果里。 */
    static Map<String, Liveness> probeAll(List<String> origins) {
        Map<String, Liveness> verdicts = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (String origin : origins) {
            Thread thread = new Thread(() -> verdicts.put(origin, probe(origin)), "candidate-probe");
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
        long deadline = System.currentTimeMillis() + DEADLINE_MS;
        for (Thread thread : threads) join(thread, deadline);
        return verdicts;
    }

    /** 探到的死地址（用来在候选链里跳过它们）。 */
    static List<String> dead(List<String> origins, Map<String, Liveness> verdicts) {
        List<String> dead = new ArrayList<>();
        for (String origin : origins) {
            if (verdicts.get(origin) == Liveness.DEAD) dead.add(origin);
        }
        return dead;
    }

    /** 除了 `other` 之外还有没有活着的地址（未知不算）。 */
    static boolean anyAliveOtherThan(List<String> origins, Map<String, Liveness> verdicts, String other) {
        for (String origin : origins) {
            if (origin.equals(other)) continue;
            if (verdicts.get(origin) == Liveness.ALIVE) return true;
        }
        return false;
    }

    /**
     * 从 origin 解出要连的地址：scheme 决定默认端口，authority 里的端口优先，IPv6 带方括号。
     * 这里会解析域名（Socket.connect 不接受未解析的地址）；解析没有超时，但探测整个跑在后台线程上，
     * 调用方只等自己那份预算，等不到就当"未知"。
     */
    static InetSocketAddress addressOf(String origin) {
        String base = Redeem.authorityOf(origin);
        if (base == null || base.isEmpty()) return null;
        boolean secure = origin.startsWith("https://");
        int at = base.lastIndexOf('@'); // 去掉 userinfo（正常不会有）
        if (at >= 0) base = base.substring(at + 1);
        if (base.startsWith("[")) {
            int close = base.indexOf(']');
            if (close < 0) return null;
            String host = base.substring(1, close);
            String rest = base.substring(close + 1);
            int port = rest.startsWith(":") ? parsePort(rest.substring(1)) : (secure ? 443 : 80);
            return port < 0 ? null : new InetSocketAddress(host, port);
        }
        int colon = base.lastIndexOf(':');
        String host = colon < 0 ? base : base.substring(0, colon);
        int port = colon < 0 ? (secure ? 443 : 80) : parsePort(base.substring(colon + 1));
        if (host.isEmpty() || port < 0) return null;
        return new InetSocketAddress(host, port);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port > 0 && port <= 65535 ? port : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static void join(Thread thread, long deadline) {
        try {
            thread.join(Math.max(0, deadline - System.currentTimeMillis()));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private Probe() {
        // 纯工具类
    }
}
