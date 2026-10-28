# opencode-android-client

[![Android](https://github.com/chyax98/opencode-android-client/actions/workflows/android.yml/badge.svg)](https://github.com/chyax98/opencode-android-client/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/chyax98/opencode-android-client)](https://github.com/chyax98/opencode-android-client/releases)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

[English](README.md) · **中文**

opencode 的 Android 客户端。用 `opencode pair` 配对后，把 opencode 官方 Web UI 全屏装进 WebView：
不改写界面、不注入 JS 桥、没有账号、没有遥测。支持多台服务器、切台保活，配对失败会用人话说清原因。

<img src="docs/assets/icon.png" width="96" alt="icon">

- **多台服务器**：每台各自一套 cookie 和站点数据，互不串；从悬浮球或桌面快捷方式切换
- **切台不重来**：切走的页面停着，切回来直接挂回去（滚动位置、没发的草稿、事件流都还在）；
  用着一台时提前把另一台的 TCP/TLS 热上
- **配对说人话**：本机地址、码失效、地址不是 opencode、服务还在启动、证书问题……各说各的，
  不会只甩一句 `net::ERR_*`
- **原生连接页**：Material 3，配色/圆角/字号对齐 opencode 自己的设计 token，深浅色跟系统
- **面小**：没有 `@JavascriptInterface`、没有后台服务；凭据只存在 App 私有目录（`allowBackup=false`）

Android 8.0+（API 26），正式包约 1.7 MB。

## 安装

从 [Releases](https://github.com/chyax98/opencode-android-client/releases/latest) 下载最新的
`oc-client-*.apk` 安装，或者自己构建（见下）。

## 配对：从 `opencode pair` 到二维码和链接

配对码是在**电脑上**生成的，App 只负责消费它。`opencode pair` 会打印**一张二维码 + 每条地址一条链接**。

**1. 在跑 opencode 的电脑上执行**（必须走后台服务；前台 `opencode serve` 不算）：

```bash
opencode pair            # 局域网地址
opencode pair --remote   # 同时给一条隧道地址（第一次会问是否同意对外暴露）
```

**2. 看输出**，大概是这个样子：

```
█▀▀▀▀▀█ ▄▀ █▀▀▀▀▀█     ← 终端里用字符画的二维码
█ ███ █ ▀▄ █ ███ █
...
Links work once and expire in 5 minutes:

  http://127.0.0.1:49374/auth/connect/AbC123...
  http://192.168.1.2:49374/auth/connect/AbC123...
  https://<你的隧道域名>/auth/connect/AbC123...
```

二维码里装的是 `{code, urls}`，**已经滤掉 loopback 地址**，所以扫码是最省事的一条路。
下面打印的链接列表里第一条通常是 `127.0.0.1` —— 粘到手机上等于指向手机自己，App 会拦下并说明原因。

**3. 在 App 里**（连接页）：

- 点「扫描二维码」，把镜头对准终端，或者
- 复制一条非 `127.0.0.1` 的链接 → 点输入框右侧的「粘贴」（或长按粘贴）→ 「连接」

配对码**5 分钟内有效、只能用一次**；兑换成功后 App 把 30 天的 session 存在自己的 cookie jar 里，
之后打开就直接进 Web UI。码过期了就再跑一次 `opencode pair`。

> 浅色终端注意：`opencode pair` 的字符二维码是按深色终端画的，浅色主题下看起来是反色。
> App 的解码器两种极性都认，不用为了扫码去改终端配色。

## 日常使用

- 打开 App 直接回到上次停留的页面（每台服务器各自记）
- 在根页面连按两次返回 → 服务器列表；也可以点悬浮球，或长按桌面图标 →「切换服务器」
- 配对失败（比如服务还在启动，503）时链接会留在输入框里，再点「连接」就是真正的重试，码没被花掉
- 上次停的页面打不开了，会回首页并说一声，不静默丢状态

## 多台服务器

管理页每行是 `名字 · 种类` + 完整地址：点按切换、长按删除、`⋮` 改名 / 复制地址 / 删除。
每台服务器按 origin 隔离 cookie 和 WebView 存储，会话列表、最近项目互不干扰。

悬浮球点一下先弹面板（故意不直接换页，避免误触），列出所有服务器 +「服务器管理 ›」。
只在看 Web UI 时出现；可以拖动、松手吸附到最近的边，位置会记住。

## 自己构建

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # JDK 17+ 即可
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest                   # 231 个用例，全在 JVM 上
./gradlew :app:assembleRelease                     # R8 + 资源收缩
```

签名、CI、以及两个依赖坑（zxing → androidx.fragment、kotlin-bom 对齐）见 [docs/build.md](docs/build.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/design.md](docs/design.md) | 架构、多服务器切换、配对与凭据边界、已知取舍 |
| [docs/build.md](docs/build.md) | 构建、签名、CI、依赖坑 |

## 已知限制

- **地址带路径前缀**（反代挂在 `/prefix` 下）：能配上，但上游 Web UI 固定用 `location.origin`，进去多半不对；
  配对成功时会提示。
- **一个凭据只用于兑换它的那条地址**：换隧道域名、或从隧道退到没配对过的局域网地址，要重新配一次。
- **session 30 天过期**（或服务端密码变了）：冷启动会确认一次，不行就提示重新 `opencode pair`，
  而不是停在 Web UI 自己的登录屏上装作还连着。
- **原生连接页目前只有中文**（Web UI 本身的语言由 opencode 自己决定）。


## 许可

[MIT](LICENSE)。图标是 opencode 官方标识（`packages/identity/mark.svg`），版权归上游；
这个项目是非官方客户端，与 opencode 官方无关。
