import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 拿 App 里那一版 zxing（core 3.4.1）跟新一版（3.5.3）分别试试：
 * 正色/反色的二维码各自认不认，以及加了 ALSO_INVERTED 之后认不认。
 * 专门用反射碰 ALSO_INVERTED，这样一个 class 能在两个版本上跑。
 */
public class QrDecodeProbe {
    static final String TEXT = "{\"code\":\"9-2CnHmvEh2gpRyP5mUdoQ\",\"urls\":[\"https://oc-client.tunnel.example\"]}";

    public static void main(String[] args) throws Exception {
        BitMatrix qr = new QRCodeWriter().encode(TEXT, BarcodeFormat.QR_CODE, 0, 0);
        int scale = 8;
        int w = qr.getWidth() * scale;
        int h = qr.getHeight() * scale;
        boolean[] dark = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dark[y * w + x] = qr.get(x / scale, y / scale);
            }
        }
        boolean[] flipped = new boolean[dark.length];
        for (int i = 0; i < dark.length; i++) flipped[i] = !dark[i];

        System.out.println("二维码尺寸 " + qr.getWidth() + "x" + qr.getHeight() + " 模块");
        for (boolean invert : new boolean[] {false, true}) {
            for (String mode : new String[] {"不给提示", "加 ALSO_INVERTED"}) {
                System.out.println("  " + (invert ? "反色（深色终端）" : "正色（浅色终端）") + " + " + mode
                        + " → " + decode(invert ? flipped : dark, w, h, mode));
            }
        }
        Package pkg = BarcodeFormat.class.getPackage();
        System.out.println("  zxing core: " + (pkg.getImplementationVersion() == null ? "未知" : pkg.getImplementationVersion()));
    }

    static String decode(boolean[] pixels, int w, int h, String mode) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        if (mode.equals("加 ALSO_INVERTED")) {
            try {
                hints.put((DecodeHintType) DecodeHintType.class.getField("ALSO_INVERTED").get(null), Boolean.TRUE);
            } catch (Exception error) {
                return "这一版没有 ALSO_INVERTED（" + error.getClass().getSimpleName() + "）";
            }
        }
        try {
            Result result = new MultiFormatReader()
                    .decode(new BinaryBitmap(new HybridBinarizer(new Grid(pixels, w, h))), hints);
            return "解出来了：" + result.getText();
        } catch (Exception error) {
            return error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    static class Grid extends LuminanceSource {
        private final byte[] data;
        private final int width;

        Grid(boolean[] dark, int w, int h) {
            super(w, h);
            this.width = w;
            this.data = new byte[dark.length];
            for (int i = 0; i < dark.length; i++) data[i] = (byte) (dark[i] ? 0 : 255);
        }

        public byte[] getRow(int y, byte[] row) {
            if (row == null || row.length < width) row = new byte[width];
            System.arraycopy(data, y * width, row, 0, width);
            return row;
        }

        public byte[] getMatrix() {
            return data;
        }
    }
}
