# USB2 本地交付证据

日期：2026-10-27；基线d615f75；本轮只交付候选APK，不操作车机。

- APK：`D:/geely/output/DiPlay-8600-USB2/DiPlay-8600-USB2-test.apk`，16,473,613字节。
- SHA256：`0b899d7ad3e1de89e23845123b88dfcc4dc26a33e1d9196ae660a93e9d465973`。
- 包名com.shihab.diplay.repair1.geely8600；版本0.2.10-H53-USB2/code29。
- 最终Java/Kotlin源码完整构建及相关JUnit：101 tests、0 failures/errors/skipped。筛选GeelyUsb/Usb/H52/Configuration/Layout。
- 最终原生策略：14项mock ioctl场景，Windows MSVC C11 `/W4 /WX` 编译并执行通过；无设备I/O。
- 原生最后修正后重新执行`:mobile:assembleStandaloneGeelyUsb`，BUILD SUCCESSFUL，NDK四架构通过。
- `scripts/verify-geely-usb-apk.ps1 -ExpectedUsbVersion USB2`通过：API18、ARMv7、USB2版本/opt-in、v1/v2签名及认证资产完整。
- 对本地原USB1重新运行apksigner；其签名与USB2相同，证书SHA256为f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79。
- 交付APK从最终build产物复制并读回一致。实际DEX包含准备入口/pending保护，ARMv7原生库包含新JNI入口和snd-usb-audio限定；helper与generated字节一致。
- helper SHA256：6ea0c686eb47b1397afd57f02f1631585decd4a3851588e52ce28f4ef0c67256。
- 独立主审review_usb2_recovery发现的4项P2已修正并复查闭合；最后原生兼容修正经review_usb2_final_native补充审查，无明确P1/P2阻断。
- 交付APK ARMv7库与最后一次构建的stripped产物字节完全一致。
- `git diff --check`通过；认证资产、签名私钥、APK均不进入Git。

原生兼容来源：参考项目commit90a9a09；Linuxv3.0 `drivers/usb/core/devio.c` 的`proc_ioctl`表明
USBDEVFS_CONNECT返回device_attach结果（成功可为+1）。这是上游源码参考，不等于本车OEM内核的动态验证。

产物目录保留build/test/package/签名日志、JUnitXML、native-test.log、verification-summary.json、SHA256SUMS和安装说明。
首次增量构建失败，禁用增量后成功；最终成功日志是验收依据，不以失败日志覆盖成功状态。

未覆盖：车机应用UID访问OEM桥、真实helper调用/租约取消、USB配置/驱动恢复、冷启动、插拔、实际音画触控。
今日复发状态未采集，不能声称唯一根因已证实或已彻底修复。音画卡顿仍是独立未解决问题。
