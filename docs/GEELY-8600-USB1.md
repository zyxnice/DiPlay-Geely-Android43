# 8600 USB1 测试包

本包用于验证 Android 4.3 的 8600 车机中，内核已枚举 iPhone、但 Android UsbManager 没有收录它时，能否通过原厂权限桥继续连接。未进行实车 CarPlay 验收，不能据此认定问题已修复。

## 安装与测试

1. 将交付的 APK 复制到车机，用车机文件管理器安装。
2. 打开名称为 **DiPlay 8600 USB测试** 的应用。包名为 `com.shihab.diplay.repair1.geely8600`，与原 P21 共存，无须卸载原应用。
3. 测试时先退出原 DiPlay，避免两个应用同时连接。测试包使用独立数据目录，需要重新设置原先的 CarPlay 参数，必要时重新完成手机配对。
4. 选择 USB 连接方式，用支持数据传输的线连接 iPhone，解锁手机，并处理手机上出现的授权提示。无需为此包再次调整 ADB、工程菜单或固件。
5. 若连接失败，点击连接界面的 **保存诊断报告**。API18 会保存到车机共享存储根目录，文件名为 `DiPlay-诊断报告-时间.txt`，具体位置以弹窗为准；也可拍摄完整连接日志。

卸载“DiPlay 8600 USB测试”即可移除本次应用改动。原应用数据不随本测试包卸载而删除。

## 日志判断

- `wired discovery 8600Bypass=true`：测试旁路平台守卫已匹配。若为 false，查看同一行的 API、board、display；本包没有强制对其它平台启用。
- `wired USB discovery ... framework=... sysfsApple=... recovered=... incomplete=...`：系统列表、sysfs 苹果设备、恢复对象和扫描失败的数量；只在结果变化时记录。
- `sysfsApple=0` 且 `incomplete=0`：这次扫描没读到苹果节点，应用旁路无法自行制造内核枚举。
- `incomplete` 大于 0：扫描或描述符核验不完整；不能把它当作“手机一定没接上”。
- `source=8600-oem-bridge`：本次使用恢复设备及原厂 FD 通道；不代表 Android 已授予普通 USB 权限。
- `8600 USB OEM FD connection failed: ...`：原厂桥、助手、身份检查或连接构造失败，后续字符保留具体失败类别。
- `USB configuration ready`、`wired opening iPhone USB data paths`、`NCM configuration confirmed`：继续排查的后续节点，出现这些节点仍不等于 CarPlay 已成功显示。

## 改动边界

正常收录的 USB 设备仍优先使用原 UsbManager 路径。补充扫描仅在本测试变体 metadata 开启、API18、`Build.BOARD=C3ALFUS`、`Build.DISPLAY` 以 `06.03.08600` 开头时启用。

对系统漏收录的苹果 USBMUX/iAP2 设备，应用通过现有 `usbprotect` 原厂桥启动短生命周期助手，校验 UID、nonce、枚举身份及完整描述符后，传递真实 FD。重枚举后重新扫描、重新取 FD。配置切换只针对已核验苹果设备描述符中的有效配置，可能写入对应设备的运行时 `bConfigurationValue`，不是刷写固件。

没有修改车机固件、系统文件、ADB、USB Host/Device 角色或 USB 节点权限，没有安装 root，也没有使用模拟器。新旁路不自动降级到 su；普通设备原有 H52 配置切换逻辑保持原行为。

## 构建

依据 `docs/BUILD.md` 准备 JDK25、Gradle9.5、Android SDK37 和项目 NDK。显式设置 `DIPLAY_AUTH_ASSETS_DIR` 指向已授权且包含两项 offline-mfi 认证资产的本地目录，再执行：

```powershell
./gradlew.bat :mobile:assembleStandaloneGeelyUsb
```

本任务同时生成 API18 可读取的独立 `assets/geely-usb/helper.dex`，不依赖应用主 DEX 或 Kotlin。认证资产不能写入 Git；本次仅复用用户指定 P21 APK 已携带的两项资产，不在文档中公开其内容。

交付校验脚本为 `scripts/verify-geely-usb-apk.ps1`，用于核对包名、版本、API18、ARMv7、旁路 metadata、v1 签名、helper DEX035 及认证资产哈希。构建和主机回归不能验证实车 Binder 放行、sysfs 可读性、USB 硬件枚举和 CarPlay 完整链路。
