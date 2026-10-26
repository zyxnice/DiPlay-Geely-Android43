# 8600 USB 应用旁路实现

- 授权：用户明确要求按已交付方案修改 DiPlay，优先原厂权限桥，交付可安装测试 APK；不修改车机固件、不使用模拟器。
- 追加授权（2026-10-26）：用户要求 fork 到本人 GitHub 仓库并提交推送本次源码。目标为 zyxnice/DiPlay-Geely-Android43；此授权替代下文最初“不发布/推送”的限制，仅涉及本次源码、测试、构建脚本和说明，不上传认证资产、密钥或APK，不向上游提交PR。
- 基线：main，HEAD 48a8c8977fa5dc52a367ac4e1b5c5e18b897bbab；开始时工作区干净。
- 依据：docs/BUILD.md；shared transport/orchestration 的现有实现；D:/geely/analysis/usb8600/app_only_bypass/DiPlay应用层绕过可行性.md 及两份权限/FD 分支证据。
- 成功口径：实现受限 OEM FD 路径与完整描述符解析，接入 iPhone 发现/权限/重枚举/NCM，相关主机测试通过，ARM/API18 helper 与测试 APK 构建、签名、资源身份核对通过，交付 APK 和安装说明。实车 Binder/USB/CarPlay 验收仍未完成。
- 非目标：不改/system/framework/内核/固件，不改 ADB/USB role/persist 属性，不 chmod/chown USB 节点，不安装 root，不自动 su，不操作非 Apple 设备，不改变正常构建和无线/CH341 行为，不发布/推送。
- 变更必要性：正常 Manager 会拒绝未收录设备；必须一起改变发现与打开来源，不能只加重试。Decision: code-change。
- 风险：高（新增受限特权 FD 提供者），交付前需新独立 agent 审查实际 diff；无实车权限验证，不宣称修复完成。
- TDD：Mode off；不强制红绿流程；对描述符边界、身份约束、协议、旁路接入与正常分支做有意义回归。

## 实现任务与所有权

1. descriptor agent：纯 Java GeelyUsbIdentity / GeelyUsbDescriptorParser，测试；设备对象构造/内核扫描/连接元数据访问器。只拥有 compat/GeelyUsbIdentity.java、compat/GeelyUsbDescriptorParser.java、compat/GeelyUsbDeviceAccess.java 及自己的测试。
2. bridge agent：GeelyUsbOemBridge / GeelyUsbFdBroker / GeelyUsbFdHelper，受限 open/config 操作、真实 FD 传递、UID/nonce/超时、测试；不改其他源码。
3. build agent：构建环境与 helper.dex 资产任务、独立 geelyUsb 测试构建变体、包名/版本/应用名、认证资产与签名校验；仅构建文件/变体资源/构建脚本。
4. 根：IphoneUsbHost、CarPlayController、UsbDeviceLayoutReader、H52UsbConfigurationFix 的接线、诊断、生命周期/重枚举缓存恢复；协调接口，最终测试、独立审查和 APK 交付。

## 固定协作接口

包均为 com.shilapi.xcertplay.compat，Java 源码确保 API18/standalone helper 可编译。

- GeelyUsbIdentity: immutable sysfsName, busNumber, deviceNumber, vendorId, productId, descriptorSha256；constructor validates; devicePath() 返回规范/dev/bus/usb/NNN/NNN。
- GeelyUsbDescriptorParser.parse(byte[]): ParsedDevice，完整 device/config/interface/endpoint 结构；保留所有 configuration/alt，最多1MiB，严格检查长度/数量，hasAppleTransport()仅接受AppleUSBMUX(255/254/2)或iAP2(255/253/1)；避免触碰Apple键盘等仅HID设备。
- GeelyUsbFdBroker(Context): isBridgeAvailable(); open(GeelyUsbIdentity): OpenedDevice{ParcelFileDescriptor descriptor, byte[] rawDescriptors, GeelyUsbIdentity identity} implements Closeable; setConfiguration(GeelyUsbIdentity,int):void。
- GeelyUsbFdHelper: 仅 main/open/config，使用Identity和Parser；随APK提供单独 helper.dex；CLI只接受严格受限参数；桥仅启动固定helper，不暴露任意命令执行入口。
- GeelyUsbDeviceAccess(Context): static isEnabled(Context)；discover(List<UsbDevice> frameworkDevices):List<UsbDevice>（返回合并后列表，正常优先，仅H53/SDK18/测试metadata开启）；isRecovered(UsbDevice)；hasAccess(UsbDevice)；open(UsbDevice):UsbDeviceConnection；static descriptorsFor(UsbDeviceConnection):byte[]?；static setConfigurationIfRecovered(UsbDevice,UsbDeviceConnection,int):boolean（false代表非旁路，旁路失败抛异常）。
- DeviceAccess自建对象保留全部接口，独立描述符数据关联连接WeakHashMap；打开必须重验身份；不伪称Android授权。根为PermissionRequest增加明确OemBridgeRoute分支，避免把它当Android许可。
- 旁路只在测试APK application metadata com.shilapi.xcertplay.GEELY_USB_BYPASS=true、SDK18、Build.BOARD=C3ALFUS、DISPLAY以06.03.08600开头时启用。

## FD 协议与安全约束

助手只用应用私有数据目录，不创建永久服务。抽象本机socket名随机；nonce至少128位。助手验证服务端peer UID等于目标App UID；App验证助手peer UID=0；双方验证nonce、协议版本和期待身份。成功首字节携带单个SCM_RIGHTS FD，立刻清除发送队列并立即接收 ancillary FD；其后以定界二进制传完整描述符。失败/超时/额外FD必须全部关闭；8秒硬退出助手，不让原厂daemon长期阻塞。配置操作仅允许描述符中存在、含目标Apple传输的非零配置，且再次核验节点与身份。

## 构建/交付

- helper.dex 只编译 Helper+Identity+DescriptorParser，不依赖Kotlin/MultiDex；D8 min-api18，DEX035检查。
- 独立测试 applicationId=com.shihab.diplay.repair1.geely8600，应用名DiPlay 8600 USB测试，与原P21共存，避免未知签名导致覆盖失败/卸载数据丢失。
- 认证资产仅复用已授权P21原包中的offline-mfi两文件到ignored本地目录，以显式DIPLAY_AUTH_ASSETS_DIR打包并校验哈希；不输出密钥、不提交资产。
- 安装包只交付本地，不安装到车机，不模拟器，不推送提交。
