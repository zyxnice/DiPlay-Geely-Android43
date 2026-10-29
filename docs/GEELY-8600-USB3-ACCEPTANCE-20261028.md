# USB3 A/B/C 实车验收（2026-10-28）

最新状态：A无明显改善；B音乐明显改善、画面仍不理想；C队列及软恢复均完成短测，用户认为与B无明显差别，按计划回退B授权修复包。整体画面流畅度未通过。C详细实现、日志与边界见[USB3-C记录](GEELY-8600-USB3-C-DELIVERY-20261028.md)。

晚间后续：在B上安装connect2连接恢复候选，正常入口无拔线软件重连36.540秒和21.380秒，后一轮补发启动请求120ms后接受TCP。用户确认画面/音乐正常；这不是画面流畅度通过，也不能称每次快速连接已解决。完整包身份、日志和剩余USB初始化延迟见[连接等待记录](GEELY-8600-MEDIA-HANDSHAKE-20261028.md)。

## 开始前证据

- 用户停车就绪并授权按原计划安装A；本机既有ADB转发直接可用，本轮未操作107服务器。
- 只读身份：API18 / C3ALFUS / 06.03.08600.H53.00060，ADB shell uid2000。
- 当前安装：USB2/code29，codePath `/data/app/com.shihab.diplay.repair1.geely8600-2.apk`。
- 车机现装APK与本地已保存USB2回退包MD5回读一致；本地回退包已有SHA256和签名校验。旧APK全量拉取因热点慢已中止，部分文件不是完整备份。
- 当前运行/持久USB配置均为 `accessory,ncm,adb`、role1，前台为原厂桌面，框架未列出Apple设备。没有在采样窗口内取得连续播放基线，不将这一状态当作流畅度对照。
- 初始日志中的FATAL为原厂 `ecarx.membercenter.service.MemberCenterService` 数据库版本错误，并非本次DiPlay候选，未处理该无关服务。

## 候选

A：`D:\geely\output\usb3-ab-20261027\DiPlay-8600-USB3-A.apk`，11452564字节；SHA256 `7636edadbf6c7713054f3130c643dc9ff536350429227415b1c261309514d3ad`。
现场证据目录：`D:\geely\output\usb3-car-acceptance-20261028`。

## A 安装与第一轮连接

- 07:44–07:47：恢复转发后分11段上传并合并，远端11452564字节、MD5 `bb424ebe31918045f4b8608e8805063e` 与候选一致。
- `pm install -r` 返回 Success，回读版本 `0.2.10-H53-USB3-A` / code29。没有卸载或清除数据。
- 应用默认配置回读为 `stage=A, network=true, urgentVideo=false, nativeMedia=false`。
- 应用正常启动后自行准备USB；只读回查运行配置为 `accessory,ncm`、role0，Apple设备 `05ac:12a8` 出现。未通过ADB手工修改USB配置。
- 07:48与07:49两次连接均进入 configuration 6、USBMUX/iAP2和VPN/NCM attach；日志有 `authentication-succeeded`、`wiredAvailable=true`、`carplay-start-session`。未取得AirPlay首帧，用户反馈仍在等待或显示错误，不能判定连接或性能通过。
- 用户按提示重插后，旧会话报告 `USB device detached` 并自动重试。07:52:49首次授权后，应用进行USB模式重新枚举，07:52:52再次请求USB权限；UI dump证实屏幕为系统USB访问授权窗口。该拔插引发的detach不作为前两轮无画面的根因。
- 现场日志及UI证据保存在输出目录的 `A-session-0750.txt`、`A-session-0752.txt`、`A-session-after-replug.txt`、`A-ui.xml`、`A-live-0753.txt`。私有日志通过既有OEM命令桥只读导出；未改变应用配置或原厂服务。

## 重插授权后的音视频证据

- 通过正常系统授权窗口点击“确定”后，07:53:24起已有音频解密、视频统计及后续触控发送记录。没有为恢复连接手工修改USB属性或驱动。
- `A-playing.txt` 保存至07:54:05的应用日志；`A-live-0753.txt` 保存对应连续logcat。已结束本次专用logcat采集进程，未停止ADB服务或转发。
- 8个视频统计窗口 `shown` 为11.6～27.9 fps；这是应用统计，不是物理屏幕实测帧率，静止画面及操作差异也影响其值。
- 视频解密调用各窗口平均25.502～72.990毫秒，采样最大569.140毫秒；音频也有接收序号缺口（本样本窗口合计2253），未定位缺口发生在发送、USB网络还是接收调度，不能直接称为网络丢包率。
- 这批计时为调用经过时间，含调度与GC影响，不等于纯CPU执行时间。nativeOpens=0、Java计数增长且authFailures=0，与A默认设置一致。
- 首次连接停滞在控制会话、重插重新枚举并授权后进入媒体会话，是已观察的先后关系；尚未证明前两次无画面的根因，也不能归因于本轮性能改动。
- 用户实车反馈：“仍有明显卡顿，跟昨天没有任何区别提升”。结论：A默认设置下音视频链路建立，但流畅度未通过；没有USB2同片段统计，不宣称定量改善。
- 下一项只对照B默认原生媒体解密，保留A的基础网络优化、画面优先级关闭与1536×576/30fps设置，避免同时改变调度。A的画面优先级开关尚未单独实车验证。

## B 安装检查点

- 已核对相同车机身份与当前A版本；B分11段上传，远端11472894字节、MD5 `1a6be416a455ad45a137ac8f5ac3d951` 与本地候选一致；SHA256为交付记录中的 `2ae171b34b0c0235bb2fa9dd5352169495e7b8e82141f20a9b8bea4193c84e2c`。
- 覆盖安装返回Success，回读版本 `0.2.10-H53-USB3-B`。A安装包仍保留在车机 `/data/local/tmp/DiPlay-8600-USB3-A.apk` 可覆盖回退，未卸载清数据。
- 安装后启动命令及日志采集暂未返回设备数据；定向断开/重连ADB后返回 `failed to connect`，启动命令结束为 `error: closed`。已请用户重启Termius Remote转发。不能把安装成功写成B已连接或原生路径已生效。
- 08:00:35用户重启转发后ADB恢复，确认同车机、USB3-B版本且前台原厂桌面；重新正常启动B。08:01:17新日志PID11437，配置为 `stage=B, network=true, urgentVideo=false, nativeMedia=true`，仍1536×576/30fps/H.264。
- B首次会话通过认证并发送CarPlay启动请求，但日志出现 `ncm bulk-out not ready; retaining bridge for retry`，NCM收到IPv6邻居请求并尝试回复，尚无AirPlay TCP会话/媒体解密证据；已请求用户重插USB重建会话。该日志说明当前发送未就绪，不足以证明驱动或性能改动是根因。
- `B-session-0801.txt` 实际仍是A旧PID25772日志，文件名不代表B样本；有效B启动证据为 `B-session-started.txt` 与 `B-start-full.txt` 中PID11437及08:01时间窗。

## B 重插后的原生路径证据

- 用户确认重插，08:04:40前台为系统USB授权窗口且configuration=2；通过正常窗口点击确定后，设备切换configuration=6，应用继续自动建连。
- 08:05:17进入AirPlay SETUP，08:05:19媒体原生自检通过。视频、音频的nativeOpens持续增长，javaOpens=0、nativeFallbacks=0、authFailures=0，确认实际使用B原生路径。
- 初步窗口：08:05:29视频解密平均1.943ms、音频0.212ms；08:05:34视频2.938ms。首次窗口包含初始化与调度成本，不作稳态对照。相比A早期视频25.502～72.990ms、音频约数毫秒的样本，调用耗时显著降低，但尚未构成同帧/同负载定量基准。
- 视频窗口仍有maxGap=1200ms和readMaxMs=717，说明降低解密调用耗时尚不等于消除所有画面间隔；当前没有证据把剩余间隔唯一归因于USB、解码器或手机发送。
- 已请用户以同一缓存音乐和列表滑动反馈音乐、画面分别是否改善，整体流畅度和长测仍未验收。
- `B-playing.txt` 保存至08:06:11，包含10个音频、10个视频接收窗口。视频各窗口解密平均0.410～6.446ms、音频0.209～0.582ms（含首窗口）；音频接收序号缺口合计0。末次累计视频nativeOpens=994、音频nativeOpens=6754，均无Java回退和认证失败；音频播放统计writeErrors=0。上述数据只覆盖该采样窗口，不代表后续不会卡顿。
- 已停止本轮专用连续logcat进程并保留 `B-live-replug.txt`，没有停止应用、ADB或热点转发。等待用户对音乐/画面的分别反馈；B仍为当前车机版本。
- 用户随后反馈：“当前音乐流畅度比以前好了非常多，但是画面的流畅度仍然不高，比之前可能稍微好了一点点”。据此B音乐体验明显改善，画面仅轻微改善且未达到目标；不将原生解密生效或音频样本改善写成整体流畅度通过。保留B继续定位视频环节，长测/冷启动尚未完成。
- `B-video-followup.txt` 记录08:09后续样本：08:09:30/35/40接收15.2/18.2/13.5fps，对应提交显示15.2/18.2/13.3fps，recoveries=0。源码核对 `VideoStats.onReceived` 位于媒体提交入队前，`onRendered` 在MediaCodec释放输出供显示时计数；二者不是物理屏幕刷新率。当前窗口不支持“主要卡在解码积压”的结论，接收调度/网络/手机产帧仍待区分。
- 下一项拟仅启用包内“优先处理画面”作可逆对照。打开DiPlay设置页命令期间ADB无回应，尚未点击开关，未确认设置页显示；本次未修改画面优先级。已请求用户恢复Termius转发，USB先保持不动。

## B 画面优先级对照检查点

- 用户恢复转发后08:13:41核对同车机、USB3-B。正常打开设置页，期间系统USB授权窗口通过正常点击确定处理。
- UI实际回读：基础优化=true、原生解密=true、画面优先=false、分辨率80%、音乐缓冲300ms；仅点击“优先处理画面”开关，再次UI回读其为true，另外两个性能开关仍true。证据：`B-priority-before.xml`、`B-priority-after.xml`。
- 对该应用执行force-stop并正常重启以读取新会话快照，未清除数据；新PID30798在08:14:55通过iPhone认证。随后ADB不再回应，定向重连尚未恢复。
- 当前持久设置已开启画面优先，但尚未取得新会话options或两个视频线程nice=-8回读，也未取得播放窗口；不能把设置保存当作线程生效或效果通过。已请用户反馈当前画面/音乐并恢复转发。若音乐恶化，恢复该开关false并重新建立会话；原生解密保持开启。

## 晚间续测（19:22–19:32）

- 本晚仍为USB3-B，`network=true, urgentVideo=true, nativeMedia=true`，1536×576/30fps/H.264硬件解码；没有重新安装或修改DiPlay源码。
- 19:22未枚举到Apple设备。用户重插并通过重新枚举后的系统USB授权后，19:24:39进入AirPlay媒体会话。没有手工改变USB角色或解绑驱动。
- 19:24:50初始窗口：接收与提交显示均22.0fps，maxGap356ms，recoveries=0；视频解密平均3975us、最大78510us，nativeOpens累计251、Java/回退/认证失败均0。触控到帧平均131ms、最大150ms，仅2次。该短窗口包含启动，不能作为稳态改善结论。证据：`B-evening-playing.txt`。
- 当场读取PID5214的airplay-screen（TID23962）、carplay-video（TID23973）proc stat，两者nice均为-8，已确认画面优先级实际生效。尚无用户确认的同场景流畅度对照结果。
- 19:25:48新建CarPlay页面；用户明确确认是自己重新打开，不能记录为应用自行崩溃。随后控制认证通过，但未进入媒体会话。原厂会员中心另有异常日志，未证明其与本次故障因果关系，未操作该服务。
- 19:29:58经正常UI点击一次“重试连接”，旧attempt4资源释放日志均completed=true，新attempt5认证成功，但仍未恢复媒体。19:30:25日志出现NCM bulk-out became ready；此前发送未就绪。证据：`B-evening-ui-retry.txt`、`B-evening-retry-result.txt`、`B-evening-retry-logcat.txt`。
- 19:31仅强制停止并正常重启DiPlay，未清数据，新PID884；再次认证成功。19:31:48的tcp6显示fe80::2:7000监听且手机对应连接处于03（SYN_RECV），支持握手尚未完成，不是视频解码阶段卡住。日志有手机邻居请求及车机7000端口输出；这些日志没有完整TCP flags/数据包，不能唯一归因于手机、NDP或USB发送。证据：`B-evening-process-restart.txt`、`B-evening-process-result.txt`、`B-evening-tcp6-after-restart.txt`。
- `B-evening-priority-sample.txt`文件实际是用户重开后的连接日志，不包含有效性能窗口；不要按文件名认作稳态样本。应用日志每次新页面会重置，需按时间和PID核验。
- 当前优先恢复媒体连接，再继续音乐与滑动列表验收；画面改善仍未通过，不能以nice=-8生效替代体验验收。
- 19:32:58再次读回，先前半连接已消失，仅7000监听保留。tun0累计收到7个邻居请求、发出7个邻居公告，不能声称内核完全没回应NDP；`Ip6InHdrErrors=54`为本次接口累计计数，缺少逐包对应，尚不能归因。证据：`B-evening-network-counters.txt`。已请用户确认手机解锁/授权提示，当前保持连接页等待，不再循环重启。

## 剩余验收

- 用户确认屏幕画面及音乐听感；保持相同片段和配置进行后续对照。
- 音画、触控、重插与长测，用户观感与日志分别记录。

不手工改车机USB角色/配置，不卸载清数据；有回归则保留证据并回退同签名USB2包。

## 历史传输中断（已恢复并完成A安装）

07:40开始上传A；07:41结束返回 `failed to read copy response: EOF`，exit=1。即使输出同时显示11452564字节已发送，也不能认定远端文件完整。随后ADB显示offline；尚未执行pm install。已请用户重启Termius Remote转发。
当时恢复时以远端大小与MD5复核，随后分段上传成功，详见前文。当前恢复入口为B安装后的ADB重连，不再重复上传或安装A。
