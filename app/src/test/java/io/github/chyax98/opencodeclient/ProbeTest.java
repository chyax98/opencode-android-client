package io.github.chyax98.opencodeclient;

import org.junit.Test;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 候选地址探测（B 方案）的测试。真的开/关本地端口来验 liveness，纯函数部分直接断言。
 *
 * <p>探测只做裸 TCP connect：不带凭据、不发 HTTP，所以这里连 HTTP 服务都不用起。
 */
public class ProbeTest {

    @Test
    public void aListeningPortIsAlive() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            assertEquals(Probe.Liveness.ALIVE, Probe.probe("http://127.0.0.1:" + listener.getLocalPort()));
        }
    }

    @Test
    public void aClosedPortIsDead() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }

        assertEquals(Probe.Liveness.DEAD, Probe.probe("http://127.0.0.1:" + closed));
    }

    @Test
    public void aNameThatDoesNotResolveIsDead() {
        assertEquals(Probe.Liveness.DEAD, Probe.probe("https://no-such-host-opencode-android-client.invalid"));
    }

    @Test
    public void junkIsDeadInsteadOfThrowing() {
        assertEquals(Probe.Liveness.DEAD, Probe.probe(""));
        assertEquals(Probe.Liveness.DEAD, Probe.probe("不是一个地址"));
        assertNull(Probe.addressOf(""));
        assertNull(Probe.addressOf("https://host:99999"));
    }

    @Test
    public void theAddressFollowsTheSchemeThePortAndThePrefix() {
        // 默认端口按 scheme 补；显式端口优先；反代的路径前缀不影响要连的地址
        assertEquals(80, Probe.addressOf("http://host.example").getPort());
        assertEquals(443, Probe.addressOf("https://host.example").getPort());
        assertEquals(8443, Probe.addressOf("https://host.example:8443").getPort());
        assertEquals(49374, Probe.addressOf("https://host.example:49374/prefix").getPort());
        assertEquals("host.example", Probe.addressOf("https://host.example/prefix?x=1").getHostString());
        assertTrue(Probe.addressOf("http://[fd00::1]:49374").getHostString().startsWith("fd00"));
        assertEquals(49374, Probe.addressOf("http://[fd00::1]:49374").getPort());
        // userinfo 不能当主机名
        assertEquals("host.example", Probe.addressOf("https://user:pass@host.example").getHostString());
    }

    @Test
    public void theDeadOnesAreCollectedAndUnknownStaysUnknown() {
        List<String> origins = Arrays.asList("a", "b", "c");
        Map<String, Probe.Liveness> verdicts = new HashMap<>();
        verdicts.put("a", Probe.Liveness.ALIVE);
        verdicts.put("b", Probe.Liveness.DEAD);

        // 没探出结论的（c / 不存在的）都当"不是死"
        assertEquals(Collections.singletonList("b"), Probe.dead(origins, verdicts));
        assertTrue(Probe.anyAliveOtherThan(origins, verdicts, "b"));
        assertFalse(Probe.anyAliveOtherThan(origins, verdicts, "a"));
        assertFalse(Probe.anyAliveOtherThan(origins, Collections.emptyMap(), "a"));
    }

    @Test
    public void probingAllGivesAVerdictForEachAddress() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        String alive = "http://127.0.0.1:" + closed;
        try (ServerSocket listener = new ServerSocket(0)) {
            alive = "http://127.0.0.1:" + listener.getLocalPort();
            Map<String, Probe.Liveness> verdicts = Probe.probeAll(Arrays.asList("http://127.0.0.1:" + closed, alive));

            // 并行探：快的两条都该有结论（黑洞那条才可能缺席）
            assertEquals(Probe.Liveness.DEAD, verdicts.get("http://127.0.0.1:" + closed));
            assertEquals(Probe.Liveness.ALIVE, verdicts.get(alive));
        }
    }

    @Test
    public void anEmptyListProbesNothing() {
        assertTrue(Probe.probeAll(Collections.emptyList()).isEmpty());
        assertTrue(Probe.dead(Collections.emptyList(), Collections.emptyMap()).isEmpty());
    }

    @Test
    public void theAddressIsResolvedSoConnectCanUseIt() {
        // Socket.connect 不吃未解析地址（会 UnknownHostException），所以这一步就得解析
        assertEquals("localhost", Probe.addressOf("http://localhost:1234").getHostString());
        assertEquals(1234, Probe.addressOf("http://localhost:1234").getPort());
    }
}
