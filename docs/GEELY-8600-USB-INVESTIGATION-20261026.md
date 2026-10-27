> 历史阶段报告：以下未连接/未验证描述仅指早期阶段。后续已实车连通但仍卡顿，请先阅读 [最终接手记录](GEELY-8600-HANDOFF.md)。

# 8600 USB 连接排查（2026-10-26）

## 本轮结论

已确认一个诊断缺陷并修复源码：`DiagnosticRedactor` 的 `pass(word|phrase)?` 子串规则把 `8600Bypass` 和 `bypass=...` 当作敏感信息，整行丢弃。旧 USB1 APK 的关键旁路日志因而不会进入应用界面/导出报告。此次仅把状态字段改为 `8600KernelDiscovery` / `kernelDiscovery`，没有放宽密码过滤规则。**这修复的是可观测性，不是已证实的连接根因。**

照片的 `MfiReady` 仅表示本地认证提供方准备完毕，不表示与手机认证成功。随后 `WaitingForIphone` 表示设备发现未返回可用 iPhone；当前证据尚不能区分旁路守卫未通过、内核无 Apple 节点、sysfs 读取失败或解析失败。照片缺少两条旧日志不能证明运行的是旧 APK。

## 输入与身份

- 仓库基线：`5ab0777`。
- 固件：`D:/Geely/nl_update_06.03.08600.H53.00060.zip`。
- APK：`D:/Geely/DiPlay-8600-USB1-test.apk`，SHA256 `e5e364f6d2775e0d22ae4cbf32a647fd872cfa9f65a2a770ad69cf88e0bc7cea`。
- REA/JADX 核实 APK 包名 `com.shihab.diplay.repair1.geely8600`、版本 `0.2.10-H53-USB1` / 29、minSdk18。未从车机拉取已安装 APK，因此不能证明照片中的安装包与本地文件一致。
- 固件 `build.prop`：API18、BOARD `C3ALFUS`、DISPLAY `06.03.08600.H53.00060`，符合代码的平台条件；应用 metadata 与现场运行状态仍须实车核实。

## 逆向证据

REA 的 JADX 已工作。对实际 USB1 APK 的 `GeelyUsbDeviceAccess.construct`、`discover` 进行了方法级反编译，确认 APK 包含仓库中的反射构造和 sysfs 扫描实现，且包含旧 `bypass` 日志字段。

REA Evidence：

- APK inventory：`ev_31c48db8e669b5b7e55d7f30f591faa9fe30d7b5d0646de728ead2667bdbb01d`。
- `construct`：`ev_503fcc283c8c6da9c6800524603c049a5bf6aadd1936a61f9875b283deeea018`。
- `discover`：`ev_10f55ad3abee94f982a9c5b2a9aa53c4d5937f94c1ca9ae9cd3a620c5af7e8b8`。
- 完整导出：`D:/Geely/analysis/8600-usb/rea-apk-evidence.json`。

固件 ODEX 使用已安装 JADX 包内的 baksmali，在同包 framework boot classpath 下反优化，再由 smali 组装为分析用 DEX，最后用 JADX CLI 反编译选定类。生成物不是刷机文件，也不是原始源码。原生指令使用独立 Capstone/pyelftools 读取，不冒称 REA 原生反编译结果。REA 当前原生引擎不可用；其无引擎 ELF 导出检查单独保存在 `rea-evidence.json`。

固件原件哈希：

- `system/framework/framework.odex`：`687a565f838cc91e5faa806ae21a01a9102a6db54d22479870f519f34e667bcc`。
- `system/framework/services.odex`：`19f58525a5b63791a38c6a881e33634ea930e54393e6b169a16629d8e93c6a6e`。

| 问题 | 本轮观察 | 证据位置（分析目录下） |
| --- | --- | --- |
| API18 USB 构造器是否匹配 | `UsbEndpoint(int,int,int,int)`、`UsbInterface(int,int,int,int,Parcelable[])`、`UsbDevice(String,int,int,int,int,int,Parcelable[])` 均存在，匹配 APK | `framework-usb.dex.txt` 的类和方法元数据；`framework-smali/android/hardware/usb/` |
| FD 打开接口是否匹配 | `UsbDeviceConnection.open(String,ParcelFileDescriptor)` 存在并转调 native_open；本轮未验证其 native FD 复制实现 | `UsbDeviceConnection.java` |
| 桥接口是否写错 | 原厂 Stub 的 `TRANSACTION_sendShellCmd=0x16`；byte[] 输入、byte[] 输出；Provider 转调 `NativeCommond.sendCmd` | `framework-smali/com/neusoft/c3alfus/projectservice/IUsbProtectService$Stub.smali`；`UsbProtectProvider.java` |
| 普通 USBManager 是否可打开漏收录节点 | `openDevice` 要求设备已在 mDevices；缺失时抛异常，再授予普通 USB 权限不能补齐这个列表 | `UsbHostManager.java` |
| 系统有哪些过滤 | 排除 hub 类9、boot HID 类3/子类1；任何接口命中也会放弃整个设备；没有在该 Java 方法发现 Apple VID 专属拒绝逻辑 | `UsbHostManager.java` 的两个 isBlackListed 和 usbDeviceAdded |
| 是否整个总线被资源黑名单限制 | 本固件资源 `0x01070021=config_usbHostBlacklist` 的数组为空 | `framework-res/resources/res/values/public.xml` 与 `arrays.xml` |
| 原生收集接口是否可影响上述过滤 | `libandroid_servers.so` 的 `0xcabc` 处回调枚举描述符，收集 type4/type5，传入 Java；该范围内未见按 VID 直接拒绝 Apple 的分支 | `libandroid_servers-usb.asm.txt`（Thumb 线性解码含 literal pool，不能将整个输出均视作可达指令） |

注意：JADX 对 framework 资源常量可能使用新 Android SDK 的错误符号别名。`UsbHostManager.java` 中自动显示的 `config_backupHealthConnectDataAndSettingsKnownSigners` 不是此固件真实资源名；应以 smali 数值 `0x01070021` 和该固件 `public.xml` 的映射为准。

## 尚未解决的现场问题

扫描首先由普通应用读取 `/sys/bus/usb/devices` 的 vendor、枚举信息和完整 descriptors。OEM 特权助手在找到可恢复设备之后才参与。任何单设备读取、严格描述符解析或反射异常都会变成 `incomplete` 计数；旧日志缺陷又隐藏该计数，故界面只留下“等待”。

以下都是待验证分支，不是已证实根因：

1. `kernelDiscovery=disabled`：运行包/metadata/系统守卫问题。
2. `sysfsApple=0,incomplete=0`：当次应用扫描未发现 Apple 节点；需要结合内核枚举、端口、线缆及手机 USB 状态定位。
3. `incomplete>0`：读文件、描述符解析或对象恢复失败；需要完整异常日志。
4. `recovered>0` 后失败：继续排查 OEM 服务、助手、FD 和重枚举；当前照片没有证明已到达此阶段。

## 获取旧 APK 中被隐藏的证据

旧 APK 在应用脱敏之前已将信息写入 Android logcat 的 `xcertplay-usb` tag，故无需先安装新 APK 才能取到关键日志。新增只读采集脚本：

```powershell
./scripts/collect-geely-usb-diagnostics.ps1 -AdbPath D:/Geely/.tools/platform-tools/adb.exe
```

前提是车机已有可用且授权的 ADB 连接。复现失败后运行；脚本不清空日志、不修改 USB 角色、权限或固件。多设备必须显式传 `-Serial`。输出本地报告；其中 shell 对 sysfs 的可读性不等于应用 UID 的可读性。未连接设备时脚本报错退出，不生成空成功报告。

本轮 `adb devices -l` 无设备，不能替用户完成真机采集。

## 验证与交付边界

- 独立 JDK21 + JUnit4 执行现有纯 Java `GeelyUsbIdentityTest`、`GeelyUsbDescriptorParserTest`：8项通过。
- 新增 Kotlin 日志回归用例覆盖两种守卫状态、成功/失败扫描摘要与密码字段拒绝；本轮未执行这些 Kotlin 用例。独立测试编译器下载未完成，已停止该下载，不能将上述8项 Java 测试描述成日志回归已通过。
- 采集脚本 PowerShell 语法检查通过；无设备路径按预期报错。
- 完整 Android 构建、安装、OEM Binder/FD 真机操作、iPhone 完整握手均未执行。原 USB1 APK 未修改；源码改动不会自动进入现有安装包。
