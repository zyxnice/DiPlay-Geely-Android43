# USB1 测试 APK 交付证据

日期：2026-10-26；实现基线 HEAD 48a8c8977fa5dc52a367ac4e1b5c5e18b897bbab。用户后续授权fork并提交推送；目标为 zyxnice/DiPlay-Geely-Android43，最终提交信息以该仓库记录为准。

## 最终产物

- 文件：`D:/geely/output/DiPlay-8600-USB1/DiPlay-8600-USB1-test.apk`
- 大小：16,485,316 字节。
- SHA256：`E5E364F6D2775E0D22AE4CBF32A647FD872CFA9F65A2A770AD69CF88E0BC7CEA`
- 包名 `com.shihab.diplay.repair1.geely8600`，versionCode29，versionName `0.2.10-H53-USB1`，应用名“DiPlay 8600 USB测试”。与 P21 独立安装。

## 当前实际验证

- JDK25、Gradle9.5、SDK37，`:mobile:assembleStandaloneGeelyUsb` 构建成功。日志在产物目录 `build-and-tests.log`。
- 最终单轮相关筛选 JUnit XML 读回：80 tests、0 failures、0 errors、0 skipped；含新增 Identity2、Parser6、Protocol9，以及配置扫描、NCM配置守卫、旧USB传输、USBMUX等既有回归。不同轮次不相加，不代表完整项目所有测试。
- 六个新 Java 类已对官方 API18 android.jar 编译；独立 helper 只含 Helper/Identity/Parser 及内部类，共11类，无 Kotlin/主APK依赖。
- 根重新执行 `scripts/verify-geely-usb-apk.ps1`：minimum API18、ARMv7、metadata true、v1/v2签名通过；两项显式认证资产与指定 P21 提取源哈希一致。
- 本机 Android Debug 签名证书 SHA256：`f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79`。不使用原作者签名，不宣称可覆盖原 P21。
- APK 中存在最终诊断源码标记 `wired USB discovery `、`8600Bypass=`、`discoverySummary`；打包 helper 与最新 generated 文件相等，DEX035，SHA256 `CB891721EF4DE38BAF27CED13830A474A8705190BC87093403AF55CF80194741`。
- 全新独立 reviewer 对实际工作树 diff、任务自有新增文件及最终诊断改动只读审查：未确认 P1/P2，无阻断测试包交付项。仅建议性结论，不代替实车验收。
- `git diff --check` 通过。认证文件、keystore、构建输出均未加入 Git。
- 用户追加fork/提交授权后，重新执行 `:shared:testDebugUnitTest --tests '*GeelyUsb*' --tests '*Usb*' --tests '*H52*' --tests '*Configuration*' --tests '*Layout*'`，本轮88项、0失败/错误/跳过。只更新Git授权/证据说明，没有改变已交付APK对应实现源码；不同轮次不相加。

## 证据位置

产物目录包含安装说明、`apk-verification.log`、`build-and-tests.log`、`junit/TEST-*.xml`、`verification-summary.json`、`SHA256SUMS.txt`。构建工具及完整中间日志保存在 `D:/geely/analysis/build-tools`，不要求用户为安装包配置开发环境。

## 尚未验证

没有车机连接，没有使用模拟器。未验证实车 sysfs 可读性、OEM Binder 实际放行、root helper 启动、SCM_RIGHTS 真机传递、USB 重枚举和完整 CarPlay 通路。旁路以“内核已有 Apple USB 节点”为前提，不能解决内核没有枚举、线材、端口或 Host 角色故障。不修改固件、系统文件、ADB、USB角色或节点权限；可切换对应苹果设备的运行时USB配置。
