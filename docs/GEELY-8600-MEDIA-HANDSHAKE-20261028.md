# 8600 控制通道后媒体会话等待排查

日期：2026-10-28。状态：connect1已装车并有无重插恢复证据，快速稳定连接仍未验收；connect2候选验证中。

## 目标与范围

用户在C无明确流畅度收益、回退B后明确要求解决“CarPlay控制通道运行中，正在等待媒体会话”的长等待。目标是在现有USB链路下更快、可重复进入CarPlay，避免手工重插；不更改系统USB持久配置、全局VPN授权或无关原厂服务。

当前父版本为USB3-B授权修复包，20:18:51覆盖回退已成功，20:19:27 iAP2认证成功但媒体未进入。C代码保留，性能结论见USB3-C交付记录。分辨率1920x720/60、音乐缓冲300ms及native/network/urgent均保留。

## 已证实与未知

- 用户截图19:52:14含authentication-succeeded，B/C均曾长等待；该状态不是C媒体队列独有。
- 当前回退B的监听fe80::2:7000存在；20:20采样没有建立的7000连接。此前19:31另一失败尝试曾有SYN_RECV，不能混作同一时刻。
- 现有NCM日志仅列IPv6地址、协议和端口，缺TCP flags，不能唯一归因NDP、TCP或手机行为。
- owner候选：Iap2WiredControlClient处理4300/4301；CarPlayVpnService拥有TUN与AirPlay监听；Ipv6NcmBridge做Ethernet/TUN转换；EthernetIpv6Codec修补NA的MAC选项。需定位实际断点后决定代码修改。

## 最小诊断

本地 `D:/geely/output/usb-handshake-20261028/tun-handshake.c` 是临时只读AF_PACKET采样器，仅绑定当前tun0并在重建时换绑；只输出IPv6 TCP7000端口头部及ICMPv6类型，不输出媒体/认证正文、不注入包、不修改路由。90秒或250条自动退出。ARMv7构建在API18实机成功加载，普通shell因权限不足退出；经既有身份守卫OEM桥运行。

第1轮20:22开始，`tun-handshake-1.txt`，从实际等待界面的正常“重试连接”触发。必须保留运行结果并核对临时进程退出。尚未提出根因，尚未修改产品源码。下步对比失败与成功的SYN/SYN-ACK/ACK、邻居发现和应用日志时间。

## 断点证据与修复候选

第1轮只订阅IPv6协议，不能用它判断OUT缺失；第2轮改为ETH_P_ALL且仍只绑定tun0、只输出IPv6头部，补齐发送方向。两个探针已按90秒上限结束，本机ps复查无遗留。

第2轮明确：20:23:54.109手机NS(target=fe80::2)，内核20:23:54.110生成NA；20:23:54.984又一组NS/NA。应用日志对应首个USB OUT在20:23:53.15开始，20:23:55.16报告not ready；线程到20:23:57.16才取到第一份NA，又等2秒后丢弃，第二份NA同样超时。20:24:02.98才出现bulk-out became ready，手机重新DAD/RS，但不再启动媒体TCP。证据为tun-handshake-2.txt与B-startup-drop-excerpt.md；后者是当时工具回显摘录，后续导出环形日志已滚动，未伪装成完整原始logcat。

已证实的直接缺陷是NcmUsbBridge.send对首批零进度写入直接return，继续取TUN下一包，导致初始邻居发现应答丢失。为何本固件/手机OUT约十秒才可用仍未知；不能把修复候选写成整个等待根因已闭环。

Change Necessity: code-change。单改UI倒计时或盲目重试iAP2不会恢复被丢弃的网络包；修复NTB发送owner的启动交付语义。PatchShape为有界初始重试；canonical owner=NcmUsbBridge，独立NcmStartupWrite只负责首个完整块前的期限，不创建新队列或新线程。
Minimality: 保持现有一次只发一个NTB、同块同序号；15秒内仅零进度重试，正部分写入立即返回原owner判失败，完整首块成功后恢复原单包deadline。额外内存仅现有block，取消在每次实际传输前checkOpen，单次最多原2秒等待。潜在风险是负USB结果是否可能掩盖内核部分消费、手机十秒后是否仍接受邻居答复，需review及实机验证。
TDD Mode off / skipped；采用诊断驱动回归，不假称先红后绿。测试覆盖零进度到成功、整个期限、正部分写不重放、立即失败退避/取消、就绪后不延长期限。独立review_ncm_startup审查实际diff，验证构建保持B阶段、USB默认过滤器及同签覆盖。

当前仅候选实现，尚未装车验证。没有改鉴权、iAP2消息、系统路由或USB模式；失败会明确结束该传输，不无限等待或自动重插。

## 独立审查与离线验证

审查发现Linux/Android负bulk结果可能丢失已传长度，已把重试限定为NTB字节数严格小于实际outEndpoint.maxPacketSize的单短包；等于边界或跨包的数据一律不因负结果整块重放。正部分写仍由原owner报错。补充512/513/16384/20000边界测试，复查无未关闭阻断项。

最终完整构建成功；713项测试中710通过、3项hostJNI未提供库而跳过、0失败。API18/v1/v2签名、包名/code/B阶段、ARMv7、USB过滤器和认证资产核验通过。保留C源码但B阶段两项C策略关闭；当前候选不包含C性能策略生效。

候选`D:/geely/output/usb-handshake-20261028/DiPlay-8600-USB3-B-connect1.apk`：16575651字节，MD5 `e788dac843baa6a2ec8823f001bd3d3e`。
精确SHA256：`273ac4ba8975237008c4febeadbe17d0e697908878b033a8467d0b2f44498042`。
gzip传输封装10784921字节，MD5 `1c4ca449e723a0b71792dd9dd6455850`。20:31开始通过15556上传，仍需车机回读哈希才安装；不能以代码检查替代接通验证。

## connect1现场结果与下一步

上述包在回读gzip/APK大小与MD5后于20:38:16覆盖安装成功。20:39第一轮：0x4301在20:39:05.150发送，USB OUT到20:39:16.900才成功（13018ms/7次），排队NA随后送出；未进入TCP。AF_PACKET记录内核每次NS后立即产生NA，不能归咎内核不回答。20:43向手机链路地址进行3次ping全部成功，RTT2.060–6.147ms，但媒体未自行建立。证据connect1-live-logcat.txt、tun-connect1.txt、tun-connect1-ping.txt、ncm-ping.txt（output/usb-handshake-20261028）。

20:44经应用正常“重试连接”按钮远程重试，USB全程未拔插。第2轮15秒OUT期限失败，现有Activity transport-error路径自动重试；第3轮20:44:49.390启动、20:44:59.850发送4301、20:45:06.730 OUT成功（7713ms）、20:45:06.780 TCP接受、20:45:10.890首帧渲染。用户确认“已进入，期间没有重插或手动重试”。这是无物理干预恢复证据，不是每次快速连接验收。首次到首帧共21.5秒，外层两轮总等待更长。

usb-out-status临时探针通过单独fd读GET_STATUS/GET_INTERFACE被usbfs以EBUSY拒绝，不能根据零初始化字节声称halt=0；GET_CONFIGURATION为6。探针90次/每秒一次自动退出，不显式claim、不reset、不发control OUT。后续核对Linux v3.0 devio.c表明接口/端点control IN也会经checkintf尝试隐式claim；当前接口已被应用占用，实际全部EBUSY。不可把此探针用于接口空闲期，下一次应在应用已授权connection内取状态，避免旁路改变接口归属。

### connect2变更判断

Change Necessity: code-change。现场残余路径是OUT恢复后原媒体请求已未继续，控制循环却无限健康。非代码重插/手动点击不能满足用户要求。现有owner：NcmUsbBridge拥有实际完整写入状态，CarPlayVpnService拥有TCP接受事实，Iap2WiredControlClient拥有4301和等待期限；controller仅接线。共享方法调用方全量rg确认，新增回调null保持control-only调用者原行为，无线不接入。

最小修改：纯WiredMediaStartup策略只跟踪首次请求，若请求时OUT未成功，等待OUT成功且500ms回复宽限仍无TCP才补发一次同一4301；TCP接受永久解除本attachment恢复动作；首次请求25秒无TCP结束此次尝试，通过原transport-error自动重试。后续4300不得延长期限。正常已接通后恢复原control recv轮询时长。不更改认证、持久USB配置、媒体/性能参数。

这是针对启动竞态的有界恢复候选，**USB OUT为何延迟仍未知**，不能据此声称底层根因已修复。与Legacy首次TCP watchdog思想一致，但没有复制其lwIP/mDNS/RA/重选interface等无当前证据的分支。connect1单短包重试保留原安全边界。

PatchShape: readiness recovery + first TCP timeout；CanonicalOwner如上；Minimality为有明确现场触发的局部缓解，不增加常驻线程/队列、不影响活跃TCP附件。Pre-edit owner-fit: control循环只接线，期限规则独立纯policy；service增加其自有TCP latch，代际检查防旧accept污染新附件。TDD off；增加时序回归并由新的review_media_startup独立审查；通过前不安装。

### connect2安装前验证

独立审查发现超时快照与TCP接受的竞态，已修复：服务提供attachment generation，读取旧代返回null；expireStartupIfPending在service同一monitor内核对active/代际/首TCP并释放，锁外发原transport-error通知；控制循环仅在原子认领成功后结束，认领失败则解除监视、保持CSM。补发前重查firstTCP但不持服务锁发CSM，属于一次best-effort公告，不宣称手机端严格原子无重复。3项Robolectric通过真实service listener+localhost TCP覆盖超时旧快照、新代保护、一次到期通知；策略另6项时序测试。

最终完整构建成功：722项、719通过、3 hostJNI跳过、0失败。独立review_media_startup复核无未关闭安装前阻断。APK核验API18、v1/v2、包名/code/B阶段、USB过滤器与认证资产通过；相比connect1所有ZIP条目名称相同，内容变化仅classes2/3.dex和三份签名文件，其余包含所有native/helper/资源完全相同。

connect2 APK：13536859字节，SHA256 `4c910ba924deb75337b289733f3bc814893f16bc6d0f0e1367f351f657331b47`，MD5 `417ef6c04a3e3c6354b3af883de68938`，路径`output/usb-handshake-20261028/DiPlay-8600-USB3-B-connect2.apk`。利用远端已核验connect1的未变化ZIP区段重建原签名字节，只上传literal gzip7135920字节；本地重建逐字节相等。assemble-connect2.sh在源包/literal MD5匹配且目标不存在才生成新路径，最终MD5匹配才接受；无重签/改包。传输仍进行中，安装与实车结果待补。

### connect2现场验证

21:00远端重建完成，MD5 `417ef6c04a3e3c6354b3af883de68938`、13536859字节均匹配后覆盖安装Success；package lastUpdateTime=21:00:35。新PID22621，不清数据、不卸载、未物理拔插。

覆盖安装后的第1轮21:01:11.770记录outputReady=false/tcpAccepted=false；15秒OUT失败后现有transport-error自动恢复。第2轮21:01:28.850启动、21:01:33.000请求、21:01:35.140 OUT成功、21:01:35.200 TCP、21:01:39.450首帧；本轮10.6秒，不能用它掩盖第1轮等待。此轮500ms内TCP已到，因此没有触发主动补发，不能称补发策略已经实证有效。

21:02从正常主页“断开连接”→“通过USB连接”开始第二组无物理拔插测试。21:02:36.210启动，第1次15秒OUT失败，21:02:57.590自动新一轮；21:03:09.090 OUT成功，21:03:09.150 TCP，21:03:12.750首帧。合计36.540秒，仍偏慢，但不用用户拔线或手动重试。

第3组21:04:40.350从正常USB入口启动，21:04:44.000发送4301且outputReady=false。21:04:57.250 OUT成功（14231ms），21:04:57.900新策略补发一次，21:04:58.020 TCP接受，21:05:01.730首帧。总计21.380秒；补发到TCP120ms，TCP到首帧3.710秒。此轮实证覆盖了“OUT恢复后还未建立TCP”的补发分支；先前只有OUT恢复仍空等的connect1对照保留，但不是严格随机实验。

用户随后确认“画面、音乐都正常”。21:07截图connect2-final.png显示CarPlay音乐应用，已读取；connect2-final-log.txt为最后应用日志。连续日志保存connect1-live-logcat.txt（文件名沿用，按PID22621与时间识别connect2），探针tun-connect2-first.txt/tun-connect2-reconnect.txt。保存完成后核对PID54568命令行，仅停止本次logcat采集；原15556直连隧道仍device，未停止。全部临时探针已自动退出并ps核实。

## 交付边界与后续定位

- 已安装保留connect2，B音频与USB默认授权修复继续保留；用户无物理拔插完成恢复，画面/音乐正常。代码、APK与排查记录已保存，本轮未提交/推送混合工作区。
- 目标“无需重插、自行从控制通道等待恢复”本次已覆盖；目标“每次快速进入”仍needs-verification，36.5秒是明确残余，不能称为彻底根治或保证秒连。未做车机断电冷启动、手机重启或长时间驾驶验收。
- 已知局部缺陷：早期短NTB发送失败直接丢包、OUT恢复后原媒体请求不续接。两项分别在发送owner与控制owner修复/有界缓解。未知：USB OUT为何启动阶段会NAK/超时十几秒。应用层SDK只给负值，外部fd状态探针被EBUSY阻断，不能区分设备halt、主机端点状态或手机内部初始化。
- 未实施“跳过重复setInterface”猜测：正常断开后实读bAlternateSetting由1降为0，下一次选择1是必要动作，不能只看连接中的值误判重复初始化。未新增packet-filter、lwIP、RA、USB持久配置等无当前因果证据的修复。
- 下一步最小诊断是在NcmUsbBridge已有授权连接内记录首个失败的endpoint GET_STATUS、实际USB负errno（保持原进度/不重发多包）以及CDC连接通知变化，并与手机侧初始化时间对应。需要新诊断候选及现场失败窗口；当前未知不能用更长超时、更多重试或编译通过掩盖。
- 验收结论强度：无重插恢复与当前媒体正常为实测；完整快速连接目标为部分完成。现有自动重连仅在应用运行、USB/热点状态正常时生效，不承诺后台无限续跑。
