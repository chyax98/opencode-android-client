package io.github.chyax98.opencodeclient;

import android.os.Bundle;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.journeyapps.barcodescanner.CaptureActivity;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 库默认的 CaptureActivity 锁横屏，这里换成 fullSensor（manifest 里声明）。
 *
 * <p>顺带修掉一个**真的扫不出来**的坑。{@code opencode pair} 打的是终端字符二维码
 * （`uqr` 的 `renderUnicodeCompact`），它的调色板是：
 *
 * <pre>
 *   浅色模块 → █ / ▀ / ▄（实心块，用终端前景色画）
 *   深色模块 → 空格（露出终端底色）
 * </pre>
 *
 * 也就是说它**假定终端是深色底**（深模块=底色、浅模块=前景色）。**浅色终端下整张码是反色的** ——
 * 而库默认的解码器只认正色（`DefaultDecoderFactory` 的 scanType=0 → `Decoder`），
 * 依赖的 zxing core 是 2020 年的 3.4.1，连 `DecodeHintType.ALSO_INVERTED` 都还没有。
 * 实测（同一个 jar、同一张图）：正色解得出，反色 `NotFoundException`。
 *
 * <p>好在库里自带解法：scanType=2 用 {@code MixedDecoder}，它在相邻的两次解码里分别用
 * `LuminanceSource` 和 `source.invert()` 试一遍（`toBitmap()` 里翻 `isInverted`），
 * 所以**不管终端是深色还是浅色都能扫**。代价只是"每帧只试一种极性"（30fps 下相当于 15fps 有效），
 * 这本来就是它设计的用法。
 */
public class ScannerActivity extends CaptureActivity {
    /** 0=只认正色（库默认）1=只认反色 2=混合（正反色都试）。见类注释。 */
    static final int SCAN_TYPE_MIXED = 2;

    private DecoratedBarcodeView view;

    @Override
    protected DecoratedBarcodeView initializeContent() {
        // 留个引用：库在 onCreate 里先建 view、再按 intent 装默认解码器，我们要在那之后换掉
        view = super.initializeContent();
        return view;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view.setDecoderFactory(new DefaultDecoderFactory(
                Collections.singletonList(BarcodeFormat.QR_CODE), hints(), "UTF-8", SCAN_TYPE_MIXED));
    }

    /** 解码提示：`TRY_HARDER` 是因为屏幕上的码往往有角度、反光、对比度差。 */
    static Map<DecodeHintType, Object> hints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, true);
        return hints;
    }
}
