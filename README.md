# RootRoute

RootRoute 是一个基于 Mihomo 的 **Android Root 透明代理**。它按应用接管流量并执行 Clash / Mihomo 规则分流，系统 VPN 槽位可留给其他应用。

## 功能

- **应用分流**：搜索应用名称或包名，支持系统应用筛选和批量勾选。
- **三种出站模式**：规则模式按配置分流。全局模式将已接管的连接交给 `GLOBAL` 代理组。自定义模式默认按规则，长按应用可将其设为全局。
- **节点管理**：按代理组浏览、搜索和切换节点，支持单节点和整组测速。
- **配置管理**：保存、改名、切换和更新多个 HTTPS 订阅，也可导入本地 YAML。
- **状态与诊断**：启动时检查代理状态，显示本次运行时长。可按需打开日志、清除日志和检查路由。
- **代理流量统计**：默认关闭。在设置中开启并重启代理后，仪表盘累计显示本次会话经代理节点的上传和下载量，排除直连流量。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/FirmPluvia/RootRoute/releases) 下载 ARM64 APK，在设备主用户中安装。需要 Root 管理器提供 `su`，设备内核需支持相应的透明代理、防火墙和策略路由功能。
2. 打开「配置」，添加返回 Clash / Mihomo YAML 的 HTTPS 订阅，或导入可信的本地 YAML。
3. 在「仪表盘」选择应用和出站模式，点击「开启 Root 代理」，按提示授予 Root 权限。
4. 修改订阅、出站模式、应用范围或统计选项后，关闭再开启代理以应用设置。

**默认开启 IPv4 TCP 兼容模式。** IPv4 TCP 使用 REDIRECT，IPv4 UDP 和 IPv6 使用 TPROXY。局域网、回环和组播流量会绕行，Android 系统 DNS 也可能通过原网络解析。共享 Android UID 的应用共用路由策略。关闭界面后代理可继续运行，设备重启后需要重新开启。

## 本地构建

在 Linux 上准备 JDK 17 或更新版本、Android SDK Platform 35、Build Tools 35.0.0，以及 `curl`、`zip` 和 `sha256sum`。

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk ./build.sh
```

默认输出 `RootRoute-arm64.apk`，使用临时测试签名。要构建能覆盖安装的正式版，请提供签名文件（JKS 或 PKCS#12），用下面 4 个环境变量传入，与其他项目共用同一套命名：

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk \
KEYSTORE_BASE64="$(base64 -w0 /absolute/path/keystore)" \
KEYSTORE_PASSWORD=<库口令> KEY_ALIAS=<密钥别名> KEY_PASSWORD=<密钥口令> \
./build.sh
```

`KEY_PASSWORD` 与库口令相同时可不填，默认取 `KEYSTORE_PASSWORD` 的值。签名文件和密码应保存在仓库外。旧的 `ROOTROUTE_KEYSTORE` + `ROOTROUTE_STOREPASS` 方式仍可用（密钥别名固定为 `rootroute`）。

仓库内附带可用于构建 APK 的 Mihomo ARM64 压缩包。需要重建核心时，使用 Go 1.26、Android NDK r29、`patch` 和 `unzip`，设置 `ANDROID_NDK_ROOT` 后运行 `./build-core.sh`。若产物的 SHA-256 改变，核对后同步更新 `build.sh` 中的校验值。

## GitHub Actions

- 分支推送、Pull Request 和手动运行会构建测试 APK，可在对应运行的 **Artifacts** 下载。
- 推送与应用版本一致的标签（如 `v0.3`）会构建正式签名 APK，并创建 GitHub Release。先在仓库的 **Settings → Secrets and variables → Actions** 添加四个仓库级 Secret（与其他项目同一套命名）。

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 签名文件（JKS 或 PKCS#12）的 Base64 内容 |
| `KEYSTORE_PASSWORD` | 签名文件密码（库口令） |
| `KEY_ALIAS` | 签名密钥别名 |
| `KEY_PASSWORD` | 签名密钥口令 |

## 许可证

项目使用 GPL-3.0，作者 **FirmPluvia**。内置核心基于 [Mihomo v1.19.31](https://github.com/MetaCubeX/mihomo/tree/v1.19.31)，对应上游源码、RootRoute 计数补丁和构建脚本随仓库提供。SnakeYAML 2.5 使用 Apache-2.0，许可证见 `vendor/SnakeYAML-LICENSE`。
