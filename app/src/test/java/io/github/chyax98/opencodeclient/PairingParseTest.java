package io.github.chyax98.opencodeclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** opencode pair 会产出两种 payload：打印出来的链接，和二维码里的 {"code","urls"}。 */
public class PairingParseTest {

    @Test
    public void parsesThePrintedTunnelLink() {
        MainActivity.Pairing pairing = MainActivity.parse(
                "  https://ab12cd34ef56gh78.opentunnel.xyz/auth/connect/abc_DEF-123  ");
        assertEquals(Collections.singletonList("https://ab12cd34ef56gh78.opentunnel.xyz"),
                pairing.origins);
        assertEquals("abc_DEF-123", pairing.code);
        assertNull(pairing.reason);
    }

    @Test
    public void parsesQrPayloadWithOnlyTheTunnelAddress() {
        // 默认配置（hostname 还是 localhost）下 pair 的二维码里只有隧道 https 一条。
        MainActivity.Pairing pairing = MainActivity.parse(
                "{\"code\":\"xY-1\",\"urls\":[\"https://ab12cd34ef56gh78.opentunnel.xyz\"]}");
        assertEquals(Collections.singletonList("https://ab12cd34ef56gh78.opentunnel.xyz"),
                pairing.origins);
        assertEquals("xY-1", pairing.code);
    }

    @Test
    public void putsHttpsBeforeLanSoTheFallbackGoesWideFirst() {
        // hostname 设成 0.0.0.0 之后二维码里会有局域网 http + 隧道 https 两条。
        MainActivity.Pairing pairing = MainActivity.parse(
                "{\"code\":\"c1\",\"urls\":[\"http://192.168.1.2:49374\",\"https://foo.opentunnel.xyz\"]}");
        assertEquals(Arrays.asList("https://foo.opentunnel.xyz", "http://192.168.1.2:49374"), pairing.origins);
    }

    @Test
    public void keepsTheLanAddressWhenThereIsNoTunnel() {
        MainActivity.Pairing pairing = MainActivity.parse(
                "{\"code\":\"c1\",\"urls\":[\"http://192.168.1.2:49374\"]}");
        assertEquals(Collections.singletonList("http://192.168.1.2:49374"), pairing.origins);
    }

    @Test
    public void dedupesAndDropsUnusableAddresses() {
        MainActivity.Pairing pairing = MainActivity.parse(
                "{\"code\":\"c1\",\"urls\":[\"http://a.example:1\",\"http://a.example:1\",\"not a url\",\"ftp://a.example\"]}");
        assertEquals(Collections.singletonList("http://a.example:1"), pairing.origins);
    }

    @Test
    public void explainsLoopbackLinksInsteadOfSilentlyFailing() {
        // `opencode pair` 不带 --remote 时，打印出来的第一条链接就是这个，手机上永远连不上。
        for (String link : Arrays.asList(
                "http://127.0.0.1:49374/auth/connect/abc",
                "http://127.1:49374/auth/connect/abc",
                "http://localhost.:49374/auth/connect/abc",
                "http://0.0.0.0:49374/auth/connect/abc",
                "http://[::1]:49374/auth/connect/abc",
                "http://[0:0:0:0:0:0:0:1]:49374/auth/connect/abc")) {
            MainActivity.Pairing pairing = MainActivity.parse(link);
            assertNotNull(link, pairing.reason);
            assertTrue(link, pairing.reason.contains("本机"));
        }
    }

    @Test
    public void doesNotMistakeAnIpv6PrefixForLoopback() {
        // 原来的 startsWith("[::1]") 会把这种骗过去。
        assertFalse(MainActivity.isLoopback("http://[::1].evil.example:49374"));
        MainActivity.Pairing pairing = MainActivity.parse("http://[::1].evil.example:49374/auth/connect/abc");
        assertNull(pairing.reason);
        assertEquals(Collections.singletonList("http://[::1].evil.example:49374"), pairing.origins);
    }

    @Test
    public void survivesBomUppercaseAndPathPrefixes() {
        // 扫码/粘贴时前面可能带 BOM；scheme 和路径的大小写不该影响识别。
        MainActivity.Pairing qr = MainActivity.parse("\uFEFF{\"code\":\"ab\",\"urls\":[\"HTTPS://Foo.Example:443\"]}");
        assertEquals(Collections.singletonList("https://foo.example"), qr.origins);

        MainActivity.Pairing link = MainActivity.parse("https://Foo.Example/opencode/auth/connect/ab");
        assertEquals(Collections.singletonList("https://foo.example/opencode"), link.origins);
        assertEquals("ab", link.code);
    }

    @Test
    public void rejectsThingsThatAreNotPairingPayloads() {
        assertNull(MainActivity.parse("hello"));
        assertNull(MainActivity.parse("https://foo.opentunnel.xyz/auth/connect/"));
        assertNull(MainActivity.parse("ftp://foo/auth/connect/code"));
        assertNull(MainActivity.parse(null));
        // JSON null 在 Android 的 org.json 里会变成字符串 "null"，那能过 code 正则，必须挡掉。
        assertNull(MainActivity.parse("{\"code\":null,\"urls\":[\"http://a.example:1\"]}"));
        assertNull(MainActivity.parse("{\"urls\":[\"http://a.example:1\"]}"));
        // 带 query/fragment 的链接现在是能用的（上游 pairingLink() 也只认 pathname），
        // 但 "redirect?to=真链接" 这种照样挡得住 —— 截断在前，后面找不到 /auth/connect/。
        assertNull(MainActivity.parse("https://evil.example/r?to=https://real.example/auth/connect/code"));
    }

    @Test
    public void acceptsLinksThatChatAppsAppendQueriesTo() {
        // 聊天软件会往链接上追加 ?utm=…；上游能配对，壳也不能判死。
        MainActivity.Pairing query = MainActivity.parse("https://foo.example/auth/connect/ok?utm=1");
        assertEquals(Collections.singletonList("https://foo.example"), query.origins);
        assertEquals("ok", query.code);

        MainActivity.Pairing fragment = MainActivity.parse("https://foo.example/auth/connect/ok#frag");
        assertEquals("ok", fragment.code);
    }

    @Test
    public void dropsDefaultPortsTheWayChromiumDoes() {
        // 配对里写的是 :443/:80，而 Chromium 序列化 URL 不带默认端口。不归一会让所有回调都匹配不上候选地址
        // （表现是配对永远超时、每条链接都被当外链丢出去）。
        assertEquals("https://host", MainActivity.normalize("https://Host:443"));
        assertEquals("http://host", MainActivity.normalize("http://host:80"));
        assertEquals("http://host:443", MainActivity.normalize("http://host:443")); // http 的默认端口是 80
        assertEquals("https://host:8443", MainActivity.normalize("https://host:8443"));
        assertEquals("https://host/prefix", MainActivity.normalize("https://host:443/prefix"));
    }

    @Test
    public void survivesMoreMalformedInput() {
        // userinfo 原样保留：比对上自然失败（出壳），是 fail-closed 的方向
        assertEquals(Collections.singletonList("https://user:pass@host.example"),
                MainActivity.parse("https://user:pass@host.example/auth/connect/ab").origins);
        // IPv6 字面量：真实的能用，本机的给出提示
        assertNull(MainActivity.parse("http://[2001:db8::1]:49374/auth/connect/ab").reason);
        assertNotNull(MainActivity.parse("http://[::1]:49374/auth/connect/ab").reason);
        // 超长 / 百分号编码 / 多段 path / 反斜杠
        assertNull(MainActivity.parse("https://h.example/auth/connect/" + "a".repeat(257)));
        assertNull(MainActivity.parse("https://h.example/auth/connect/ab%63"));
        assertNull(MainActivity.parse("https://h.example/auth/connect/a/b"));
        assertNull(MainActivity.parse("https://h.example\\auth\\connect\\ab"));
        // 零宽字符前缀，和 BOM 一样当空白处理
        assertNotNull(MainActivity.parse("\u200B{\"code\":\"ab\",\"urls\":[\"https://a.example\"]}"));
        // 空 urls / 只有本机地址
        assertNull(MainActivity.parse("{\"code\":\"ab\",\"urls\":[]}"));
        assertNotNull(MainActivity.parse("{\"code\":\"ab\",\"urls\":[\"http://127.0.0.1:1\"]}").reason);
    }

    @Test
    public void normalizeKeepsTheProxyPrefixButDropsQueryAndTrailingSlash() {
        assertEquals("https://host:49374", MainActivity.normalize("  HTTPS://Host:49374/  "));
        assertEquals("https://host/prefix", MainActivity.normalize("https://host/prefix/"));
        assertEquals("https://host/prefix", MainActivity.normalize("https://host/prefix?a=1#b"));
        assertNull(MainActivity.normalize("host:49374"));
        assertNull(MainActivity.normalize("ftp://host"));
        assertNull(MainActivity.normalize(null));
    }

    @Test
    public void baseAndPathHelpers() {
        assertEquals("https://host:49374", MainActivity.baseOf("https://host:49374/prefix"));
        assertEquals("https://host", MainActivity.baseOf("https://host"));
        assertNull(MainActivity.baseOf(null));
        assertEquals("/auth/connect/abc", MainActivity.pathOf("https://host:49374/auth/connect/abc?x=1"));
        assertEquals("/", MainActivity.pathOf("https://host"));
        assertEquals("/s/x", MainActivity.pathOf("https://host/s/x#f"));
        assertNull(MainActivity.pathOf(null));
    }
}
