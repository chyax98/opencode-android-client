import java.util.Base64

plugins {
    id("com.android.application")
}

// 正式包签名只从环境变量来（CI 走 secrets，本地想签真包就 export 同名的四个变量）：
//   OC_CLIENT_KEYSTORE_BASE64  keystore 的 base64（CI 用，免去往磁盘塞文件）
//   OC_CLIENT_KEYSTORE         或者直接给 keystore 路径（本地用）
//   OC_CLIENT_KEYSTORE_PASSWORD / OC_CLIENT_KEY_ALIAS / OC_CLIENT_KEY_PASSWORD
// 放在 android {} 外面：signingConfigs 的接收者是 SigningConfig，那里没有 layout。
val releaseKeystore: File? = run {
    val base64 = System.getenv("OC_CLIENT_KEYSTORE_BASE64")
    val path = System.getenv("OC_CLIENT_KEYSTORE")
    when {
        !base64.isNullOrBlank() -> layout.buildDirectory.file("ci-release.jks").get().asFile.also {
            it.parentFile.mkdirs()
            it.writeBytes(Base64.getDecoder().decode(base64))
        }

        !path.isNullOrBlank() -> file(path)
        else -> null
    }
}
val hasReleaseKey = releaseKeystore != null && releaseKeystore.exists()

android {
    namespace = "io.github.chyax98.opencodeclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.chyax98.opencodeclient"
        minSdk = 26
        targetSdk = 35
        // 版本号可以被 -P 覆盖（CI 用 run number / tag 填），本地直接 ./gradlew 还是这里写的默认值。
        versionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 3
        versionName = (findProperty("versionName") as String?) ?: "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = releaseKeystore
                storePassword = System.getenv("OC_CLIENT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OC_CLIENT_KEY_ALIAS")
                keyPassword = System.getenv("OC_CLIENT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 正式包开 R8：壳里没有反射、也没有 @JavascriptInterface（不注入任何桥），
            // 所以默认规则就够；zxing 那部分保守 keep 住（见 proguard-rules.pro）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // 没配签名也照样出一份能装的（debug 签名）：CI 没 secrets 时也拿得到产物，
                // 但**这份不能上架/长期分发**（签名不是你的）。
                logger.warn("== 没有配正式签名（OC_CLIENT_KEYSTORE*）→ 这份 release 用 debug 签名 ==")
                signingConfigs.getByName("debug")
            }
        }
    }

    testOptions {
        // Robolectric 需要真实资源（主题/图标/manifest 合并结果）。
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // targetSdk 35 是刻意的：36 会忽略 enableOnBackInvokedCallback，返回键（双按进管理页）
        // 得整体迁到 OnBackInvokedCallback 才能升，不是这次的范围。其余 lint 保持全开。
        disable += "OldTargetApi"
    }
}

dependencies {
    // 只用它的 IntentIntegrator 拿扫码结果，其余 UI 全是 WebView。
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // 连接页那点原生 UI（输入框 / 按钮 / 卡片）用 Material3：这套东西手搓 drawable 也能做，
    // 但按键态、涟漪、深色适配都要自己写一遍，不如直接用官方的。
    implementation("com.google.android.material:material:1.12.0")
    // 上面这个 AAR 的 POM 漏了它自己引用的 androidx 类（ContextCompat 等），不补会在打开扫码 Activity 时
    // ClassNotFoundException。fragment 会传递带上 androidx.core / activity / annotation。
    implementation("androidx.fragment:fragment:1.8.6")
    // androidx 那条链上混着 kotlin-stdlib 1.8.22 和老的 jdk7/jdk8 1.6.21，类重复；对齐到 1.8.22（那两个包从
    // 1.8 起就是空的兼容壳）即可。
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:1.8.22"))
    // Profile.preconnect：切到 A 之后把 B 的 DNS/TCP/TLS 先热起来（240ms RTT 上省 1–2 个来回）。
    // 只用到 WebViewCompat/Profile 这几个兼容 API，旧 WebView 上会自动退化。
    implementation("androidx.webkit:webkit:1.17.1")

    // 只测解析配对链接的那段纯逻辑；JVM 上没有真 JSON，补一个。
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    // 在 JVM 上把整个 Activity + WebViewClient 状态机跑起来（不用模拟器），见 ShellFlowTest。
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.6.1")
}
