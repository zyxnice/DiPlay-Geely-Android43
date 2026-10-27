# 8600 USB / CarPlay 跨电脑接手总记录

更新：2026-10-27。覆盖本次会话的连接、逆向、系统恢复背景和性能排查。**下一台电脑请先读本文件；早期报告中的“未连接”不再是最终结论。**

## 1. 当前结论与接手任务

- USB CarPlay 已在实车成功连接，用户确认画面、触摸、声音均可用。
- 画面和声音仍会卡顿。降低分辨率、临时停止反复崩溃的会员中心，均未解决。不能称为完全修复。
- 连接成功依赖本次运行时 USB 配置和精确解绑原厂音频驱动；尚未固化到 APK，也未验证重启/重新插线自动恢复。
- 用户于10月26日晚要求停止操作休息。10月27日本次工作仅整理/推送仓库，未恢复实车操作。
- 下一步应在持续音乐播放、卡顿正在发生时，采集音频接收线程和 UDP 队列变化。不要重复已经完成的线缆、端口、工程菜单排查。

## 2. 硬件、软件与文件

| 项目 | 已知值 |
| --- | --- |
| 车机 | GEELY NL5QJ，BOARD=C3ALFUS，Android4.3/API18 |
| 固件 | 06.03.08600.H53.00060 |
| 固件包 | nl_update_06.03.08600.H53.00060.zip，原电脑 D:/Geely/ |
| 测试应用 | com.shihab.diplay.repair1.geely8600，0.2.10-H53-USB1，versionCode29 |
| 旧应用 | com.shihab.diplay.repair1；测试时已强制停止，未卸载 |
| 本地测试 APK | D:/Geely/DiPlay-8600-USB1-test.apk |
| APK SHA256 | e5e364f6d2775e0d22ae4cbf32a647fd872cfa9f65a2a770ad69cf88e0bc7cea |
| 手机 | USB-C iPhone；会话日志报告 model=iPhone18,3、ios=27.2，未另行核对营销型号 |
| 仓库基线 | 5ab0777，main |

同一物理 USB 接口轮流插电脑、iPhone、U盘。手机线接电脑曾出现信任提示（用户报告）；U盘在正确主机模式下已枚举。USB成功后速度节点为480 Mbps。不要将“能充电”当作数据链路已建立。

固件、APK、ADB/JDK/JADX工具和完整分析目录不随本次文档提交复制。新电脑须另装 Android platform-tools；需要重新逆向时另行转移固件。原电脑原始分析存放 D:/Geely/analysis/8600-usb/；其中含固件派生物和私有运行数据，不应整目录上传公共仓库。

## 3. 新电脑首先恢复无线 ADB

1. 电脑和车机接入同一可信局域网/热点；查车机当前IP。上次地址是172.20.10.9，可能变化。
2. 执行（PowerShell；将地址替换为当前值）：

```powershell
$car = '172.20.10.9:5555'
adb connect $car
adb -s $car get-state
adb -s $car shell getprop ro.product.board
adb -s $car shell getprop ro.build.display.id
adb -s $car shell getprop ro.build.version.sdk
./scripts/collect-geely-usb-diagnostics.ps1 -AdbPath adb -Serial $car -OutputDirectory ./usb-diagnostics
```

用户已明确要求持久开启无线ADB。已写 persist.adb.tcp.port=5555，并验证 /data/property/persist.adb.tcp.port；service.adb.tcp.port=5555、adbd运行。**没有做过重启验证**。此固件 ro.adb.secure=0，网络ADB缺少通常的RSA授权边界，仅在可信私网使用，不做公网端口映射。

如果无线ADB不可达，不能假设可远程修复：先核对IP/同网/车机开机状态；必要时通过此前可用的有线ADB/本地维护入口恢复。不要为无线ADB盲改 persist.sys.usb.config，也不要同时占用需要插iPhone的同一USB口。

## 4. 连接问题的实证过程

最早照片显示 MfiReady 后一直 WaitingForIphone。MfiReady只是本地认证提供方准备好，不代表手机配对完成。

1. 初始 sys.usb.config=accessory,ncm,adb，实际角色mode1，手机充电但不枚举。
2. 切 accessory,ncm 后mode0；最初手机停止充电。恢复旧配置并车机端拔插约10秒后恢复充电。
3. 试CDP仅充电，没有手机枚举。原厂充电服务有时又切回DCP。
4. 内核日志确认 **mode0=车机HOST，mode1=车机GADGET**。属性 usb.role.switch=host 的命名会误导，不能按字面推断车机角色。
5. mode0 + 显式OTG供电 + CDP下，U盘U268（3535:1234，类08）能枚举。
6. 将OTG充电模式设为SDP后，iPhone重插出现信任/CarPlay提示，枚举05ac:12a8。
7. 内核原厂逻辑将Apple设备选到configuration2，snd-usb-audio占用2.0/2.1。日志明确报告该驱动占用interface0阻止应用切configuration6。
8. 停止旧DiPlay，精确解绑这个Apple设备的snd-usb-audio接口。用户在测试版重连并确认授权。
9. configuration6建立USBMUX/NCM；约21:11开始持续收视频/音频，用户确认触摸和声音正常。

关键内核文字：`generic_probe: set iPod configuration to 2`；`interface 0 claimed by snd-usb-audio ... sets config #6`。`device v05ac p12a8 is not supported`警告并未阻止后续枚举，不可单凭它判死刑。

## 5. 已成功的运行时状态及恢复步骤

仅适用于上述固件/硬件，**先读状态；正常播放时不要执行切换或解绑**。以下不是通用车机脚本。所有adb命令明确使用无线序列号。

成功状态：sys.usb.config=accessory,ncm；sys.usb.state相同；proc角色0；OTG charging_mode=sdp；power正常/on；pin_fault=working fine；Apple配置6。

persist.sys.usb.config仍为accessory,ncm,adb，未修改。临时usb.role.switch属性可能仍为host，与实际proc角色不一致。

仓库的 scripts/invoke-geely-oem-command.ps1 是已有原厂Binder命令封装的可移植版本。服务usbprotect transaction22（0x16）将UTF8命令作为byte[]提交给原厂服务；它具有高权限，不对其它固件尝试。

```powershell
# 先确认匹配上述硬件，且当前没有正常播放的会话。
./scripts/invoke-geely-oem-command.ps1 -AdbPath adb -Serial $car -Command 'setprop sys.usb.config accessory,ncm; echo on > /sys/class/otg_peripheral/power'
adb -s $car shell service call usbprotect 8 i32 2
adb -s $car shell cat /proc/driver/usbroleswitch/0/mode
adb -s $car shell cat /sys/class/otg_peripheral/charging_mode
adb -s $car shell cat /sys/class/otg_peripheral/pin_fault/status
```

确认mode0/sdp/供电正常后，车机端重新插入已解锁iPhone，接受授权；停止竞争的旧应用：

```powershell
adb -s $car shell am force-stop com.shihab.diplay.repair1
adb -s $car shell ls -l /sys/bus/usb/devices
adb -s $car shell ls -l /sys/bus/usb/drivers/snd-usb-audio
```

本次节点为1-1，vendor05ac/product12a8。**重新检查实际节点和驱动绑定，不能无条件照抄路径。** 本次实际成功的精确解绑命令如下；仅当当前设备与接口再次匹配时，经上述OEM封装执行：

```sh
test "$(cat /sys/bus/usb/devices/1-1/idVendor)" = 05ac &&
test "$(cat /sys/bus/usb/devices/1-1/idProduct)" = 12a8 &&
{ for i in 1-1:2.0 1-1:2.1; do
  if [ -e /sys/bus/usb/drivers/snd-usb-audio/$i ]; then
    echo $i > /sys/bus/usb/drivers/snd-usb-audio/unbind
  fi
done; }
```

然后在“DiPlay 8600 USB测试”点重连并确认授权，等待配对。只解绑目标接口；没有卸载整个音频驱动、关闭过流保护、发送CAN控制命令或删除系统应用。重新插拔可能重新绑定驱动；重启可能恢复mode1。自动化恢复仍是待实现事项。

## 6. 卡顿证据、已排除项与下一步

- 活跃播放/滑动时视频rx/shown接近，但帧到达间隔最高约0.9–1.5秒；不能据此单独认定解码器不够快。
- 音频5秒窗口曾出现199个RTP序号缺口，处理耗时峰值约102–180ms，输出queue常为0，AudioTrack writeErrors=0，estimatedQueuedFrames有时为0。
- IPv6 UDP InErrors从2517增至3119、3545；RcvbufErrors始终0。该链路为IPv6，IPv4统计正常不能排除问题。
- /proc/net/udp6中UID10062的音频候选socket曾rx_queue=0x3FFC0（262080字节），接近实际接收缓冲262142字节；有积压证据，但尚未证实具体丢包原因。不能只凭InErrors断言校验和错，也不能只凭RcvbufErrors=0排除拥塞。
- 请求0.7×/30fps对照后，最新实际协商1152×432；不能直接把UI标称倍率当实际尺寸。硬解为OMX.Freescale.std.video_decoder.avc.v3.hw-based。短窗口达29.6/29.4fps，用户仍反馈音画都卡。
- GC样本暂停5–10ms，WAIT_FOR_CONCURRENT_GC42ms；不能解释所有秒级停顿。dumpsys cpuinfo曾是约750秒前旧样本；fresh top出现约54%及约88%总负载，不能混用时间点。
- 会员中心因数据库8降到6反复崩溃。临时禁用后用户反馈没有改善，已pm enable恢复，enabled=1（原default=0），stopped=false。未清除数据或卸载。系统恢复问题交给另一个会话处理。
- 调用kill -3抓取线程不等于杀进程，但会干扰采样。当时最新dump中音频流已停止，没有airplay-audio-rx；不能用这份dump解释正在播放时的瓶颈。
- dump文件有多个相同PID段，必须选择最后一段。旧段主线程在refreshLogView/StaticLayout，最新段主线程idle、NCM入站等待read pump。不要混淆。

下一次实车优先流程：
1. 确认停车、用户准备好，恢复连接后让音乐持续播放，不要在抓取前暂停。
2. 同一时间窗口采样 /proc/net/udp6、/proc/net/snmp6、视频/音频统计、当前线程状态。记录前后差值。
3. 区分接收/解密/回调/线程调度耗时。AudioStream.runData串行接收→BouncyCastle ChaCha20Poly1305解密→回调；这是待测方向，不是已证实加密瓶颈。
4. 依据证据修复。单纯加大缓冲可能只增加延迟；不要跳过认证校验，不要盲目调CPU/关闭系统保护。
5. 后续把USB角色/SDP/目标接口解绑做成受平台与设备身份约束的兼容路径，并验证插拔、重启、旧应用竞争和回滚；本次尚未实现。

## 7. 代码入口与本次源码变更

- shared/.../compat/GeelyUsbDeviceAccess.java：API18+C3ALFUS+8600+metadata守卫；普通设备优先，补充sysfs扫描；OEM FD帮助器针对漏收录设备。
- shared/.../transport/H52UsbConfigurationFix.java：切配置；正常framework设备可能不走OEM恢复路径，snd-usb-audio占用引起EBUSY需处理。
- shared/.../transport/H52UsbReadPump.java：API18 direct UsbRequest读线程+64项队列；LegacyUsbTransfer实际调用它，不要仅凭过时注释认定同步bulk读取。
- shared/.../network/Ipv6NcmBridge.kt、VpnTunnelCompat.kt：NCM到VPN TUN IPv6桥。
- shared/.../airplay/AudioStream.kt、AirPlayCrypto.kt、StreamReceiveStats.kt：音频接收/解密/统计。
- shared/.../media/AndroidMediaSink.kt、VideoStats.kt：输出和显示统计。
- common/.../CarPlayHostActivity.kt：日志UI/设置；refreshLogView潜在负载需实测。

本次源码仅修正诊断标签：8600Bypass/bypass被DiagnosticRedactor的pass规则整行过滤，改为8600KernelDiscovery/kernelDiscovery，保留敏感字段过滤，并增加回归用例。**这是可观测性修复，连接成功来自运行时处理，并非安装了这次源码生成的新APK。** 无性能修复APK交付。

## 8. 逆向与误卸载背景

用户曾在另一会话提权卸载喜马拉雅、用户手册、违章查询、新闻、会员中心，随后USB/U盘/有线ADB异常，其它功能基本正常。原件备份可用，另一会话已恢复APK/ODEX及数据权限并核对哈希；不能把本次连接根因简单归为那五个应用。恢复后会员中心数据库版本仍不兼容，另行处理。

工程入口：原厂设置“关于”页右侧吉利Logo区域长按至少4秒，输入gm1128，发送developer_mode。逆向显示此版工程应用ModeMark.isHidden=true，ADB按钮被设为GONE；进入工程模式本身不启用ADB。不要反复找不可见菜单，亦不要用固定940/EMC/CAN测试面板代替USB恢复。

REA/JADX用于APK方法检查；ODEX通过同固件framework+API18 baksmali反优化再组DEX/JADX。ARM本地分析使用Capstone/pyelftools，不能冒称REA原生引擎成功。原厂usbprotect transaction8：0/1/2分别OTG DCP/CDP/SDP，3/4/5分别host DCP/CDP/SDP；transaction22为命令桥。transaction6带CAN及供电操作，未调用。

原厂KC2_popview充电控制：事件9切OTG DCP，事件10等待8000ms切CDP；对应host事件11/12。这解释测试中DCP回切。内核日志原路径 /data/c3_kernel_logs/7555_2026-10-26_20-03-12.log；dmesg空不代表没有事件，可能被原厂logger读取。

早期API/构造器/过滤器细节见 [历史逆向报告](GEELY-8600-USB-INVESTIGATION-20261026.md)。框架资源名以固件public.xml/smali数值为准，JADX的新SDK符号别名可能错误。

## 9. 证据迁移与隐私边界

本仓库总结保存足够的已核实参数和结论，不上传配对记录、私钥、认证资产、手机名称/标识、完整dump、OEM APK/固件或用户应用数据。确需继续逆向，可通过私有文件传输迁移原电脑以下文件：

- analysis/8600-usb/recovery/USB-ROLE-KERNEL-FINDINGS.md、CARPLAY-PERFORMANCE.md、ENGINEERING-ENTRY.md。
- recovery/live/diplay-success.txt、diplay-performance-active.txt、diplay-performance-no-member.txt、diplay-performance-07.txt、diplay-performance-threads.txt（多个dump段）。
- recovery/live/kernel-current.log、iphone-descriptors.bin；完整固件及USB1 APK按上述哈希核对。
- analysis/8600-usb/rea-apk-evidence.json、framework/services反优化产物仅作分析，不刷回车机。

脚本采集输出可能含设备身份，留本地检查后再分享。接手者不要自动上传usb-diagnostics目录。

## 10. 新会话可直接使用的任务说明

> 阅读docs/GEELY-8600-HANDOFF.md。USB CarPlay已经通过mode0+SDP+精确解绑Apple snd-usb-audio连通，但音画卡顿未解决。先恢复可信局域网无线ADB并核对硬件，必要时按文档恢复运行时USB配置。不要删除系统应用或重走工程菜单。让用户持续播放音乐，在卡顿时采样音频接收线程、IPv6 UDP队列和错误增量，定位接收/解密/调度瓶颈；以实测结果修复并验证。当前低分辨率对照没有解决问题；没有新性能修复APK。

## 11. 本次交接提交的验证边界

此前8项USB身份/描述符纯Java测试通过。10月27日尝试执行日志Kotlin回归测试，本地独立编译器未能加载K2JVMCompiler（此前下载未完成），因此不声明新回归已通过。未执行完整Android构建或车机测试；本次只提交会话已有日志字段修复、文档和脚本。PowerShell语法及git差异检查另行完成。OEM脚本新增精确硬件/固件守卫，仅做离线检查，未再次对车机执行。

