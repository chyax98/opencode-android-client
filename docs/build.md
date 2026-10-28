# 构建、签名与发布

## 环境

- JDK 17 或以上（CI 用 21）
- Android SDK：`compileSdk 35` / `build-tools 35`（Gradle 会按 `app/build.gradle.kts` 自己找）
- 不需要模拟器：231 个用例全在 JVM 上跑（Robolectric）

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :app:assembleDebug             # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest         # 全量单测
./gradlew :app:lintDebug                 # 0 error 是基线，4 条依赖版本提示是已知的
./gradlew :app:assembleRelease           # R8 + 资源收缩，约 1.7 MB
```

`./gradlew :app:testDebugUnitTest --tests '*ShellFlowTest'` 可以只跑一条。
测试是 Robolectric：真的建 `MainActivity`、拿 `WebViewClient` 手动喂回调，不需要设备或模拟器。

## 签名

正式包签名**只从环境变量来，仓库里不放 keystore**：

| 变量 | 用途 |
| --- | --- |
| `OC_CLIENT_KEYSTORE_BASE64` | keystore 的 base64（CI 用，免去往磁盘塞文件） |
| `OC_CLIENT_KEYSTORE` | 或者直接给 keystore 路径（本地用） |
| `OC_CLIENT_KEYSTORE_PASSWORD` | store password |
| `OC_CLIENT_KEY_ALIAS` | key alias |
| `OC_CLIENT_KEY_PASSWORD` | key password |

```bash
export OC_CLIENT_KEYSTORE=/path/to/release.jks
export OC_CLIENT_KEYSTORE_PASSWORD=...
export OC_CLIENT_KEY_ALIAS=...
export OC_CLIENT_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

没配变量也能出包，但会退回 **debug 签名**（构建打一行 warning）：能装能跑，不能当正式分发。
**换签名之后已装的包必须先卸载**（签名不同不能覆盖安装），卸载会清掉 WebView 数据 —— 得重新扫一次码配对。

## CI 与发布

`.github/workflows/android.yml`：

- `push` 到 `main`、所有 PR、手动触发：跑全量 JVM 用例 + `assembleRelease` + 上传 APK artifact
- 推 `v*` tag：额外用 `gh release create` 把 APK 挂到 Release

版本号由 CI 传：`-PversionCode=<run number> -PversionName=<tag 去掉 v>`；本地不传就用
`app/build.gradle.kts` 里的默认值（当前 `1.1.0`）。

发一版：

```bash
git tag -a v1.1.0 -m "opencode-android-client 1.1.0"
git push origin main v1.1.0
```

CI 的四个 secret 沿用旧名字 `OC_SHELL_KEYSTORE_*`（历史原因，工作流里映射到新的
`OC_CLIENT_*` 环境变量）。推**含 workflow 文件**的提交要求 token 有 `workflow` scope：
`gh auth refresh -h github.com -s workflow`。

## 构建时容易踩的两个坑

1. `com.journeyapps:zxing-android-embedded` 的 POM 只声明了 `zxing:core`，但它自己引用了 androidx 的类 ——
   少了 `androidx.fragment` 会在点「扫描二维码」时 `ClassNotFoundException: ContextCompat` 直接崩。
2. 补上 `fragment` 后依赖树里混着 `kotlin-stdlib` 1.8.22 和老的 `jdk7/jdk8` 1.6.21，类重复；
   用 `kotlin-bom` 对齐。
3. `CAMERA` 权限隐含"设备必须有摄像头"：manifest 里显式声明 `camera` / `camera.autofocus` 的
   `required="false"`，否则 lint 的 `PermissionImpliesUnsupportedChromeOsHardware` 会让构建不绿。
