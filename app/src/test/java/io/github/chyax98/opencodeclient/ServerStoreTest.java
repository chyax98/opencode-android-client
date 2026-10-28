package io.github.chyax98.opencodeclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** 多台服务器的落盘格式：老的/坏的数据不能把连接页带崩，配对同一台机器不能产生重复条目。 */
public class ServerStoreTest {

    private static final String TOKEN = "1700000000.abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";

    private static MainActivity.Server server(String key, String... origins) {
        return new MainActivity.Server(key, Arrays.asList(origins));
    }

    @Test
    public void roundTripsThroughJson() {
        List<MainActivity.Server> servers = Arrays.asList(
                server("https://tunnel.example", "https://tunnel.example", "http://192.168.1.2:49374"),
                server("https://other.example", "https://other.example"));
        String json = MainActivity.encodeServers(servers);
        List<MainActivity.Server> back = MainActivity.decodeServers(json);

        assertEquals(2, back.size());
        assertEquals("https://tunnel.example", back.get(0).key);
        assertEquals(Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"), back.get(0).origins);
        assertEquals("https://other.example", back.get(1).key);
    }

    @Test
    public void garbageOrTheOldFormatDecodesToAnEmptyListInsteadOfThrowing() {
        assertTrue(MainActivity.decodeServers(null).isEmpty());
        assertTrue(MainActivity.decodeServers("").isEmpty());
        assertTrue(MainActivity.decodeServers("https://a.example\nhttps://b.example").isEmpty());
        assertTrue(MainActivity.decodeServers("[{\"key\":\"not a url\"}]").isEmpty());
    }

    @Test
    public void decodeKeepsTheKeyFirstAndDropsJunkOrigins() {
        List<MainActivity.Server> servers = MainActivity.decodeServers(
                "[{\"key\":\"https://a.example\",\"origins\":[\"刚删掉的\",\"http://a.example/\"],"
                        + "\"extra\":1},{\"key\":\"https://b.example\"}]");

        assertEquals(2, servers.size());
        // origins 里没写 key 的时候补在最前，配对的地址永远是第一条候选
        assertEquals(Arrays.asList("https://a.example", "http://a.example"), servers.get(0).origins);
        assertEquals(Collections.singletonList("https://b.example"), servers.get(1).origins);
    }

    @Test
    public void mergingByKeyExtendsTheOriginsWithoutDuplicating() {
        List<MainActivity.Server> servers = Collections.singletonList(
                server("https://a.example", "https://a.example"));

        List<MainActivity.Server> merged = MainActivity.mergeServer(servers, "https://a.example",
                Arrays.asList("https://a.example", "http://192.168.1.2:49374"));

        assertEquals(1, merged.size());
        assertEquals("https://a.example", merged.get(0).key);
        assertEquals(Arrays.asList("https://a.example", "http://192.168.1.2:49374"), merged.get(0).origins);
    }

    @Test
    public void mergingByASharedAddressRecognisesTheSameMachine() {
        // 上次用隧道配的，这次用局域网那条链接配：地址有交集 → 同一台机器，key 保持不变
        List<MainActivity.Server> servers = Collections.singletonList(
                server("http://192.168.1.2:49374", "http://192.168.1.2:49374", "https://tunnel.example"));

        List<MainActivity.Server> merged = MainActivity.mergeServer(servers, "https://tunnel.example",
                Collections.singletonList("https://tunnel.example"));

        assertEquals(1, merged.size());
        assertEquals("http://192.168.1.2:49374", merged.get(0).key);
        assertEquals(Arrays.asList("http://192.168.1.2:49374", "https://tunnel.example"), merged.get(0).origins);
    }

    @Test
    public void mergingANewMachinePrependsIt() {
        List<MainActivity.Server> servers = Collections.singletonList(server("https://a.example", "https://a.example"));

        List<MainActivity.Server> merged = MainActivity.mergeServer(servers, "https://b.example",
                Collections.singletonList("https://b.example"));

        assertEquals(Arrays.asList("https://b.example", "https://a.example"),
                Arrays.asList(merged.get(0).key, merged.get(1).key));
    }

    @Test
    public void aUserGivenNameSurvivesTheRoundTripAndIsOmittedWhenThereIsNone() {
        // 名字对齐上游的 displayName：存在客户端本地，不往服务器写
        MainActivity.Server named = server("https://tunnel.example", "https://tunnel.example");
        named.name = "我的电脑";
        String json = MainActivity.encodeServers(Collections.singletonList(named));

        assertEquals("我的电脑", MainActivity.decodeServers(json).get(0).name);
        assertTrue(json.contains("\"name\""));
        assertFalse("没名字就别写这个字段", MainActivity.encodeServers(
                Collections.singletonList(server("https://a.example", "https://a.example"))).contains("name"));
    }

    @Test
    public void theStoredTokenSurvivesTheRoundTrip() {
        MainActivity.Server paired = server("https://a.example", "https://a.example");
        paired.token = TOKEN;
        paired.tokenOrigin = "https://a.example";

        List<MainActivity.Server> back = MainActivity.decodeServers(
                MainActivity.encodeServers(Collections.singletonList(paired)));

        assertEquals(TOKEN, back.get(0).token);
        assertEquals("https://a.example", back.get(0).tokenOrigin);
    }

    @Test
    public void aTokenThatDoesNotBelongToAnyOriginIsDropped() {
        // 盘上的数据可能是旧格式或被改过：tokenOrigin 不在候选地址里、或者 token 形状不对，就都别认
        List<MainActivity.Server> foreign = MainActivity.decodeServers(
                "[{\"key\":\"https://a.example\",\"origins\":[\"https://a.example\"],"
                        + "\"token\":\"" + TOKEN + "\",\"tokenOrigin\":\"https://evil.example\"}]");
        assertNull(foreign.get(0).token);

        List<MainActivity.Server> junk = MainActivity.decodeServers(
                "[{\"key\":\"https://a.example\",\"origins\":[\"https://a.example\"],"
                        + "\"token\":\"nope\",\"tokenOrigin\":\"https://a.example\"}]");
        assertNull(junk.get(0).token);
    }

    @Test
    public void mergingDoesNotCarryTheOldTokenOver() {
        // 合并只搬运 key/origins/route：token 由这次配对的结果决定（onPageFinished 那一处写），
        // 继承上一次的会在"走页面老路重配"之后留下旧 token
        MainActivity.Server known = server("https://a.example", "https://a.example");
        known.token = TOKEN;
        known.tokenOrigin = "https://a.example";

        List<MainActivity.Server> merged = MainActivity.mergeServer(Collections.singletonList(known),
                "https://a.example", Collections.singletonList("https://a.example"));

        assertNull(merged.get(0).token);
        assertNull(merged.get(0).tokenOrigin);
    }

    @Test
    public void theLastRouteSurvivesTheRoundTrip() {
        MainActivity.Server server = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"), "https://a.example/session/ses_9");

        List<MainActivity.Server> back =
                MainActivity.decodeServers(MainActivity.encodeServers(Collections.singletonList(server)));

        assertEquals("https://a.example/session/ses_9", back.get(0).route);
    }

    @Test
    public void junkRoutesAreDroppedAndMergingKeepsTheKnownOne() {
        assertNull(MainActivity.decodeServers("[{\"key\":\"https://a.example\",\"route\":\"javascript:alert(1)\"}]")
                .get(0).route);

        MainActivity.Server known = new MainActivity.Server("https://a.example",
                Collections.singletonList("https://a.example"), "https://a.example/session/ses_9");
        List<MainActivity.Server> merged = MainActivity.mergeServer(Collections.singletonList(known),
                "https://a.example", Arrays.asList("https://a.example", "http://192.168.1.2:49374"));

        assertEquals(1, merged.size());
        assertEquals("https://a.example/session/ses_9", merged.get(0).route);
    }

    @Test
    public void namesMatchTheWebUiServerName() {
        assertEquals("ab12cd34ef56gh78.opentunnel.xyz",
                MainActivity.nameOf("https://ab12cd34ef56gh78.opentunnel.xyz"));
        assertEquals("192.168.1.2:49374", MainActivity.nameOf("http://192.168.1.2:49374"));
        assertEquals("local", MainActivity.nameOf("http://127.0.0.1:49374"));
        assertEquals("local", MainActivity.nameOf("http://localhost:49374"));
        // IPv6 字面量不能在冒号上切（以前会显示成 "["）
        assertEquals("[fd00::1]:4096", MainActivity.nameOf("http://[fd00::1]:4096"));
    }

    @Test
    public void twoMachinesWithTheSameLanAddressDoNotMerge() {
        // 同一个网段里 DHCP 把同一个 IP 分给两台电脑很常见：都有隧道地址时只按局域网地址交集合并会粘成一条。
        List<MainActivity.Server> merged = MainActivity.mergeServer(
                Collections.singletonList(server("https://a.example", "https://a.example", "http://192.168.1.2:49374")),
                "https://b.example",
                Arrays.asList("https://b.example", "http://192.168.1.2:49374"));

        assertEquals(2, merged.size());
    }

    @Test
    public void aSecondPairingOfTheSameMachineStillMerges() {
        // 同一台机器的第二次配对：已有条目是"隧道+局域网"（key=隧道），新链接只给了局域网地址。
        // key 对不上，只能靠地址交集合并 —— 此时只有一边有 https，没有"两台机器"的冲突信号，必须合并。
        List<MainActivity.Server> merged = MainActivity.mergeServer(
                Collections.singletonList(server("https://tunnel.example", "https://tunnel.example", "http://192.168.1.2:49374")),
                "http://192.168.1.2:49374",
                Arrays.asList("http://192.168.1.2:49374"));

        assertEquals(1, merged.size());
        assertEquals("https://tunnel.example", merged.get(0).key);
        assertEquals(Arrays.asList("https://tunnel.example", "http://192.168.1.2:49374"), merged.get(0).origins);
    }

    @Test
    public void oneBadEntryDoesNotWipeTheWholeList() {
        // 顶层解析失败时前面已经读出来的条目要留着（整条清空等于把已配对的服务器全删了）
        List<MainActivity.Server> servers = MainActivity.decodeServers(
                "[{\"key\":\"https://a.example\"},123,\"junk\"]");

        assertEquals(1, servers.size());
        assertEquals("https://a.example", servers.get(0).key);
    }
}
