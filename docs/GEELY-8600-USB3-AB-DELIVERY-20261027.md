# USB3 A/B 安装包交付与明早验收入口

状态：A/B 代码、独立审查、本地回归、构建及 APK 检查已完成；实车音画效果、重插和冷启动仍待验。C 未实现。

## 明早先安装 A

目录：`D:\geely\output\usb3-ab-20261027`。

| 包 | 版本名 / code | 字节数 | SHA-256 |
| --- | --- | --- | --- |
| `DiPlay-8600-USB3-A.apk` | `0.2.10-H53-USB3-A` / 29 | 11452564 | `7636edadbf6c7713054f3130c643dc9ff536350429227415b1c261309514d3ad` |
| `DiPlay-8600-USB3-B.apk` | `0.2.10-H53-USB3-B` / 29 | 11472894 | `2ae171b34b0c0235bb2fa9dd5352169495e7b8e82141f20a9b8bea4193c84e2c` |

包名均为 `com.shihab.diplay.repair1.geely8600`；与已安装 USB2 相同包名、code 和证书。证书 SHA-256：`f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79`。使用覆盖安装，不卸载、不清数据。

USB2 原回退包仍在 `D:\geely\output\usb2-car-acceptance-20261027\DiPlay-8600-USB2-sysfs-test.apk`，SHA-256 `9d8118b0d8a81043fff89f0fbd5e374354d3491f445cfb11eab6eed129f9d78d`。

本次代码基于 `f524611e3c2f2b064c765da0e63d061d035ad51b`，保留为本地改动，未提交或推送；确切候选以 APK SHA 为准。A/B 使用相同生产源码，通过构建参数选择身份和 native 模块，A 不包含 `libxcertplay_chacha.so`。

## 实际变化与默认设置

### A

- event socket 启用 TCP_NODELAY；视频 socket 在 bind 前申请 512 KiB 缓冲，accept 后记录实际值。
- 设置页“显示与性能”增加 8600 流畅度选项，仅测试构建且匹配 API18/C3ALFUS/H53 8600 显示。
- “8600 基础流畅度优化”默认开；“优先处理画面（实验）”默认关。后者由两个视频工作线程自己申请 nice=-8，失败保持系统原调度，不操作 root/系统参数。
- 首轮先按默认配置测试，再单独开启画面优先级比较音乐与画面，不能把打开优先级等同通过。

### B

- 包含 A，并增加“音视频快速解密（实验）”，默认开，可关闭以回到 Java 媒体解密作对照。
- 仅视频及音频流接入 native；配对、控制、IAP 隧道继续原入口。
- native 成功返回独立明文，不覆盖原密文；认证失败仍拒绝。不支持或库加载/自检失败时保留 Java 回退。
- 统计增加纯解密窗口平均/最大耗时，以及每流 native/Java 成功、回退、认证失败等累计值；首个 native 调用含加载自检成本，比较预热后的窗口。

设置按控制器创建时固定快照，后台返回不会改变运行会话。更改后需要真正断开连接并重新打开连接；仅返回主界面、拔插后沿用同一后台控制器，不保证读取新设置。必要时退出并重新打开测试应用；明早验收由日志 `Performance: controller options=...` 确认。

关闭 A 的基础优化和调度，可在同包获得保持旧处理方式的诊断对照；它仍含统计代码，不冒称与原 USB2 二进制完全相同。

## 本轮验证证据

- A 完整构建成功：`build-A.log`；B 完整构建成功：`build-B.log`。
- 最终单测：shared 505、common 162，共 667 项，无失败、无跳过。包括三个真实会话配置快照测试、socket 开关/失败/重连测试、密码运算输入保持及回退测试。
- Windows 实际 JNI 与 BouncyCastle 对照覆盖边界长度/AAD和并发；C 测试及 Java/JNI 向量/篡改拒绝通过。不是只测 mock。
- `verify-A.log`、`verify-B.log`：API18、ARMv7/v1签名、与USB2包名/code/完整证书一致、USB2 recovery/bypass metadata、helper DEX035及两个原认证资产哈希匹配。A不带B库、B带库。
- Android 4.3/API18 ARM 模拟器：A 安装成功；B 覆盖 A 成功；A 覆盖 B 成功。A/B Activity 均有实际 Displayed 记录及 resumed 状态。首次 A `am start -W` 曾超时，后续日志确认已显示，不把该超时命令本身写成启动通过。
- 从实际 B APK 提取 ARMv7 `.so`，在 API18 ARM 模拟器运行 JNI harness：`NativeChaCha JNI PASS: RFC8439, authentication, input ownership and bounds`，见 `arm-api18-jni.log`。动态符号记录在 `arm-symbols.txt`。
- 两项独立审查：会话配置/接线/校验器审查发现的三项 P2 均修正并复审通过；B native 审查未发现本次新增 P1/P2。

模拟器不是 8600：不证明该车 CPU 性能、厂商解码器或 USB 自动重连通过。普通模拟器不满足性能平台门控；JNI probe 单独验证实际打包的 ARM 库，不将其写成整车媒体链路验收。

## 明早步骤

1. 停车，开启手机热点和原 Termius Remote 转发，手机保持解锁；在对话通知“已就绪”。
2. 重新核对 107/ADB/车机身份与当前应用版本，先留 USB2 基线，避免未经对照直接换包。
3. 覆盖安装 A，回读 `0.2.10-H53-USB3-A`；沿用昨天成功的 USB 口，如弹 USB 授权则允许。
4. 固定缓存音乐与分辨率，跑相同列表滑动、地图和音乐片段；先默认调度，再单独对照“优先处理画面”。
5. A通过后再安装B，确认日志native计数增长，再比较耗时和音画效果。回退到Java后能播放，不等于native优化已通过。
6. 出现明显声音恶化、黑屏/崩溃或重连回归，保存日志并覆盖回上一包。最终候选再做15分钟播放、应用返回、重插及正常关车再启动。

本轮没有连接或操作车机，没有部署107，也没有修改系统USB配置。现场每项结果另记，不能用此次构建或模拟器结果代替。
