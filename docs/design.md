# 设计

## 它是什么

opencode 服务端自己会发一套 Web UI。这个 App 不重写界面，只做三件事：

1. 用 `opencode pair` 的链接 / 二维码完成配对，把 session cookie 写进 WebView；
2. 全屏承载官方 Web UI；
3. 在几台服务器之间切换（每台是一个 origin，各自一套 cookie 和站点数据）。

没有 `@JavascriptInterface`、没有注入脚本、没有后台服务，也没有自己的账号体系。
服务端是唯一的数据来源，App 不缓存会话内容。

## 多台服务器

- 一台服务器 = 一个 `origin`（scheme + host + port）。WebView 的 cookie 和 localStorage 天然按 origin
  隔离，所以"切服务器"就是换一个 origin 重新加载，不需要清理或复制任何状态。
- **切台保活**：最多保留两张 WebView。切走的那张停着，切回来直接挂回视图 —— 滚动位置、未提交的草稿、
  已建立的事件流都还在。停超过 10 秒才补发一次重订阅；系统内存紧张时主动丢掉停着的那张。
- **连接预热**：用着一台时，另一台的首选地址会通过 `Profile.preconnect` 提前建好 DNS/TCP/TLS
  （不携带凭据，连接约 30 秒有效）。局域网候选不预热 —— 离开那个网络就不可达。
- 凭据只用于"兑换出它的那条地址"。二维码里可能同时有隧道和局域网地址，把 token 种到所有候选上
  等于把凭据送给可能不属于这台服务器的地址，所以不做。
- 悬浮球的面板里：切换服务器、刷新页面、页面的后退/前进、复制当前地址、扫码添加、服务器管理。
  后退/前进走的是页面自己的历史（WebView 的 back-forward list），不是壳自己的页面栈。

## 配对与凭据

`opencode pair` 在电脑上生成一个** 5 分钟、一次性**的码，并打印二维码和链接。App 收到链接后：

1. 走 API 兑换（`GET /auth/connect/<code>`，`Accept` 不带 `text/html`）拿到 token；
2. 用 token 验证这条地址确实是 opencode 服务；
3. 自己写 `opencode_session_<port>` cookie（30 天，HttpOnly），再加载 `origin/`。

走 API 而不是"让 WebView 访问链接等 302"，是为了能按状态码区分失败原因（码失效 / 不是 opencode /
服务正在启动 / 地址拒绝），也为了把 token 存下来 —— cookie 不在了就能重注，不必重新配对。
一条地址兑换失败就换下一条候选，成功的 token 只落在那条地址上。

Cookie 在浏览器里是按 host 存的（不分端口），所以服务端把端口写进了 cookie 名；
壳自己写 cookie 时沿用同一套规则。

## 本机存了什么

| 位置 | 内容 | 生命周期 |
| --- | --- | --- |
| WebView cookie jar（App 私有） | session cookie（`opencode_session_<port>`，HttpOnly） | 30 天；服务端密码变了立即失效 |
| SharedPreferences | 服务器列表（名字、候选地址、上次路由、token 及来源地址）、当前是哪台 | 直到卸载 / 清数据 |
| WebView 站点数据 | 每台服务器自己的 localStorage / IndexedDB / service worker | 按 origin 隔离 |

token 等同密码：`allowBackup=false`，不参与系统备份；`usesCleartextTraffic` 只为局域网 http 服务。

## 失败时说什么

错误提示按"用户能做什么"分类，而不是转发 `net::ERR_*`：

- 没网（当场判定，不等超时）
- 地址解析不了 / 电脑或服务没起（连接被拒）
- 证书不被信任（自签、域名不匹配）
- 401/403：token 被撤销或服务端换了密码 → 提示重新配对
- 冷加载拿到 4xx/5xx 错误页：**不算连上**，继续试候选地址
- 隧道通了但后面的服务没响应：看门狗超时后换地址

已连接页面内部的 4xx/5xx 不拦截 —— 那是页面自己的业务错误，壳不该替它弹窗。

## 已知取舍

- **不做深链**：浏览器里点配对链接不会拉起 App（需要域名校验，自签名过不了）。
- **不做 blob 下载**：Web UI 的附件/导出走 `blob:`，要注入脚本才能接；http(s) 直链已交给系统下载器。
- **不注入 CSS 放大触摸目标**：上游布局很紧凑（消息操作 20×20、相邻按钮 4–8px），全局放大热区会重叠、
  把"点输入框"变成"点发送"。这是布局问题，不是一条 CSS 能解决的。
- **不接管返回键历史**：只在页面根部双按返回进管理页，Web UI 自己的前进后退保持原样。
- **一个凭据只覆盖一条地址**：换隧道域名要重新配对（见上）。
- **地址带路径前缀**：能配上，但上游 Web UI 固定用 `location.origin`，进去多半不对；配对成功时会提示。

## 对上游的假设

这些约定一变，App 不会崩，只会表现得很奇怪：

- `/auth/connect/<code>` 按 `Accept` 分流：API 拿 JSON `{token}`，浏览器拿 302 + cookie
- cookie 名带端口；token 可以作为 Basic 密码访问 `/api/info`
- 配对码 5 分钟、一次性
- Web UI 的路由形态 `/server/<key>/...`（App 只存整条 URL，不解析 key）
- `online` 事件代表网络恢复（切回来靠它重订阅事件流）

升级 opencode 后，按"配对 → 多条地址回退 → 切服务器 → 回到上次路由"走一遍就能发现不兼容。
