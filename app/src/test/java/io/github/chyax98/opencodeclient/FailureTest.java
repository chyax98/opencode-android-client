package io.github.chyax98.opencodeclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.webkit.WebViewClient;

import org.junit.Test;

/**
 * 错误文案的纯 JVM 测试：不打壳、不需要 Android 运行时。
 * 这些句子是用户在故障时唯一能看见的东西，措辞变了就应该有人手动确认一次。
 */
public class FailureTest {

    @Test
    public void theErrorCodesMirrorTheWebViewClientConstants() {
        assertEquals(WebViewClient.ERROR_UNKNOWN, Failure.UNKNOWN);
        assertEquals(WebViewClient.ERROR_HOST_LOOKUP, Failure.HOST_LOOKUP);
        assertEquals(WebViewClient.ERROR_AUTHENTICATION, Failure.AUTHENTICATION);
        assertEquals(WebViewClient.ERROR_PROXY_AUTHENTICATION, Failure.PROXY_AUTHENTICATION);
        assertEquals(WebViewClient.ERROR_CONNECT, Failure.CONNECT);
        assertEquals(WebViewClient.ERROR_IO, Failure.IO);
        assertEquals(WebViewClient.ERROR_TIMEOUT, Failure.TIMEOUT);
        assertEquals(WebViewClient.ERROR_REDIRECT_LOOP, Failure.REDIRECT_LOOP);
        assertEquals(WebViewClient.ERROR_UNSUPPORTED_SCHEME, Failure.UNSUPPORTED_SCHEME);
        assertEquals(WebViewClient.ERROR_FAILED_SSL_HANDSHAKE, Failure.FAILED_SSL_HANDSHAKE);
        assertEquals(WebViewClient.ERROR_BAD_URL, Failure.BAD_URL);
        assertEquals(WebViewClient.ERROR_FILE, Failure.FILE);
        assertEquals(WebViewClient.ERROR_FILE_NOT_FOUND, Failure.FILE_NOT_FOUND);
        assertEquals(WebViewClient.ERROR_TOO_MANY_REQUESTS, Failure.TOO_MANY_REQUESTS);
        assertEquals(WebViewClient.ERROR_UNSAFE_RESOURCE, Failure.UNSAFE_RESOURCE);
    }

    @Test
    public void noNetworkWinsOverWhateverTheWebViewSays() {
        assertEquals(Failure.OFFLINE, Failure.describe(Failure.CONNECT, "net::ERR_CONNECTION_REFUSED", false));
        assertEquals(Failure.OFFLINE, Failure.describe(Failure.UNKNOWN, "whatever", false));
    }

    @Test
    public void hostLookupSaysTheNameCouldNotBeResolved() {
        String message = Failure.describe(Failure.HOST_LOOKUP, "net::ERR_NAME_NOT_RESOLVED", true);
        assertTrue(message, message.contains("解析不了"));
        assertTrue("人话里不该出现 net:: 前缀：" + message, !message.contains("net::"));
    }

    @Test
    public void refusedConnectionPointsAtTheComputerAndTheService() {
        String message = Failure.describe(Failure.CONNECT, "net::ERR_CONNECTION_REFUSED", true);
        assertTrue(message, message.contains("开着机"));
        assertTrue(message, message.contains("opencode"));
    }

    @Test
    public void timeoutMentionsSleepAndSlowNetwork() {
        String message = Failure.describe(Failure.TIMEOUT, null, true);
        assertTrue(message, message.contains("休眠") || message.contains("网络"));
    }

    @Test
    public void tlsProblemsSayCertificate() {
        String message = Failure.describe(Failure.FAILED_SSL_HANDSHAKE, null, true);
        assertTrue(message, message.contains("证书"));
    }

    @Test
    public void anUnknownCodeStillClassifiesTheDescription() {
        String message = Failure.describe(Failure.UNKNOWN, "net::ERR_ADDRESS_UNREACHABLE", true);
        assertTrue(message, message.contains("到不了"));
    }

    @Test
    public void aCompletelyUnknownFailureKeepsTheRawDescriptionAfterAHumanSentence() {
        String message = Failure.describe(Failure.UNKNOWN, "net::ERR_SOMETHING_NEW", true);
        assertTrue(message, message.startsWith("页面没加载成功"));
        assertTrue("认不出来时留着原文，截图/logcat 还能看出是哪一层坏了", message.contains("net::ERR_SOMETHING_NEW"));
    }

    @Test
    public void httpAuthErrorsTellTheUserToPairAgain() {
        assertTrue(Failure.describeHttp(401).contains("opencode pair"));
        assertTrue(Failure.describeHttp(403).contains("opencode pair"));
    }

    @Test
    public void http404SaysThereIsNoOpenCodeAtThatAddress() {
        String message = Failure.describeHttp(404);
        assertTrue(message, message.contains("404"));
        assertTrue(message, message.contains("OpenCode"));
    }

    @Test
    public void http502BlamesTheServiceBehindTheTunnel() {
        String message = Failure.describeHttp(502);
        assertTrue(message, message.contains("502"));
        assertTrue(message, message.contains("opencode"));
        assertFalse("别让用户以为要换地址：" + message, message.contains("换个地址"));
    }

    @Test
    public void http429SaysRateLimitedInsteadOfShowingTheStatusAlone() {
        assertTrue(Failure.describeHttp(429).contains("限流"));
    }
}
