package io.github.chyax98.opencodeclient;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.Decoder;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * 钉住扫码器认不认**反色**二维码。
 *
 * <p>背景：{@code opencode pair} 用的是 uqr 的 {@code renderUnicodeCompact}，它把**浅色模块**画成实心块
 * （█ / ▀ / ▄）、深色模块画成空格 —— 也就是按深色终端设计的。浅色终端下整张码是反色的。
 * 而库默认的解码器（scanType=0 → {@code Decoder}）只认正色，配套的 zxing core 3.4.1 里连
 * {@code DecodeHintType.ALSO_INVERTED} 都没有：反色码必然 {@code NotFoundException}。
 *
 * <p>{@link ScannerActivity#SCAN_TYPE_MIXED} 走 {@code MixedDecoder}，相邻两次解码分别试正色和
 * {@code LuminanceSource.invert()}，所以两种极性都认。这里用真的二维码位图来验，不用模拟。
 */
public class ScannerDecodeTest {
    private static final String PAYLOAD =
            "{\"code\":\"9-2CnHmvEh2gpRyP5mUdoQ\",\"urls\":[\"https://oc-client.tunnel.example\"]}";

    @Test
    public void mixedModeReadsAnInvertedCodeAndANormalOne() throws Exception {
        assertEquals(PAYLOAD, decodeWithinTwoFrames(decoder(ScannerActivity.SCAN_TYPE_MIXED), qr(false)).getText());
        assertEquals(PAYLOAD, decodeWithinTwoFrames(decoder(ScannerActivity.SCAN_TYPE_MIXED), qr(true)).getText());
    }

    @Test
    public void theLibraryDefaultCannotReadAnInvertedCode() throws Exception {
        // 负对照：库默认（scanType=0）两帧都试不出反色码 —— 这正是"浅色终端扫不动"的根因，别再把这条删掉。
        Decoder decoder = decoder(0);
        assertNull(decodeWithinTwoFrames(decoder, qr(true)));
        assertEquals(PAYLOAD, decodeWithinTwoFrames(decoder, qr(false)).getText());
    }

    /** 跟 ScannerActivity 里装的是同一份配置。 */
    private static Decoder decoder(int scanType) {
        return new DefaultDecoderFactory(Collections.singletonList(BarcodeFormat.QR_CODE),
                ScannerActivity.hints(), "UTF-8", scanType).createDecoder(ScannerActivity.hints());
    }

    /**
     * 真机上每帧只调一次 decode，混合模式每帧只试一种极性，所以"两帧之内中"才是它的真实行为。
     *
     * <p>注意失败时 {@code Decoder.decode} 返回 **null**（它把 zxing 的 NotFoundException 吃掉了），不抛异常。
     */
    private static Result decodeWithinTwoFrames(Decoder decoder, LuminanceSource source) {
        for (int frame = 0; frame < 2; frame++) {
            Result result = decoder.decode(source);
            if (result != null) return result;
        }
        return null;
    }

    /** 把 {@link QRCodeWriter} 的位图放大成一张"屏幕上的码"；inverted=true 模拟浅色终端那种反色渲染。 */
    private static LuminanceSource qr(boolean inverted) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(PAYLOAD, BarcodeFormat.QR_CODE, 0, 0);
        int scale = 8;
        int width = matrix.getWidth() * scale;
        int height = matrix.getHeight() * scale;
        byte[] pixels = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean dark = matrix.get(x / scale, y / scale);
                pixels[y * width + x] = (byte) (dark != inverted ? 0 : 255);
            }
        }
        return new Grey(pixels, width, height);
    }

    private static class Grey extends LuminanceSource {
        private final byte[] pixels;
        private final int width;

        Grey(byte[] pixels, int width, int height) {
            super(width, height);
            this.pixels = pixels;
            this.width = width;
        }

        public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < width) {
                row = new byte[width];
            }
            System.arraycopy(pixels, y * width, row, 0, width);
            return row;
        }

        public byte[] getMatrix() {
            return pixels;
        }
    }
}
