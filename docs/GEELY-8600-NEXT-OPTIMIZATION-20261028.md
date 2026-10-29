# 8600 后续优化：进入速度、清晰度、触控响应

日期：2026-10-28。状态：离线推进，新增候选尚未装车。用户已结束今天的车机调试；本轮不连接车机、不中断当前会话、不安装、不修改车机设置。

2026-10-29 归档更新：本次提交保存 [B-output1](GEELY-8600-B-OUTPUT1-20261028.md)，包含已解码输出合并及事件/视频分段统计。实车用户确认音乐正常、操作更跟手，但画面连续性仍不足。后续 output2/output3 实验单独保留，不属于本次源码归档；下文为 10 月 28 日的计划与当时基线，不代表这些目标已通过实车验收。

## 基线与已知事实

- 保留 B-connect2：`output/usb-handshake-20261028/DiPlay-8600-USB3-B-connect2.apk`，SHA-256 `4c910ba924deb75337b289733f3bc814893f16bc6d0f0e1367f351f657331b47`。这是本轮直接回退包。
- 用户确认无需物理重插或手动重试即可进入，且画面、音乐正常；并未确认快速进入、原生清晰度或触控流畅达标。A 无明显改善，B 明显改善音乐，C 无明显额外改善，因此本轮继续以 B 为基础。
- 两次正常软件重连从开始至首帧为 36.540 秒（包含首次 OUT 超时后的自动重试）和 21.380 秒。后一轮 OUT 就绪后补发启动请求至媒体 TCP 约 120ms，TCP 至首帧 3.710 秒。不能只计算成功重试轮次而省略用户已等待的时间。
- 最新实际请求为 1920×720 / 60fps，解码输出 coded=1920×736，crop=0,0,1919,719，有效画面为 1920×720。736 是对齐尺寸，不是已证实的显示错误；60fps 是请求值，不是持续实测帧率。面板物理分辨率、最终缩放和编码画质仍须分别核对。
- 一段触控采样显示 touch2frame 平均161ms、最大199ms、发送最长120ms。原指标从发送开始到随后一个视频包到达，不含 UI 到发送线程的排队，也不等于触控到屏幕亮起，更不能证明随后那一帧由该触控引发。

证据：`D:/geely/output/usb-handshake-20261028/connect1-live-logcat.txt`（包含 connect2 进程）、`connect2-final-log.txt`、`connect2-final.png` 和[握手记录](GEELY-8600-MEDIA-HANDSHAKE-20261028.md)。最后截图在音乐页面切换过程中，不能单独作为静止文字清晰度的评价图。

## 顺序与验收方法

| 工作项 | 当前判断 | 下一步及决策条件 | 实车验证 |
| --- | --- | --- | --- |
| 1. 快速进入 | 最大已知等待位于首次 USB OUT 接受数据之前，约7–15秒以上；真正原因未知 | 保留 connect2 的有界恢复；记录原始 USB 返回、调用耗时、首次输入和 CDC 链路变化。若失败与 link-up 相邻，核对启动时序；若为立即失败，再在已有授权 connection 中取错误/端点状态。证据支持后才考虑调整接口准备。 | 同一手机、线、接口，分开记录冷启动、软件重连、已有USB接入恢复；统计点击/启动至首帧的完整时间及失败率。初步工程目标为常规连接10秒以内，作为优化目标而非已达结果；不能用隐藏等待页或删除超时替代改善。 |
| 2. 清晰度 | 有效视频已经1920×720，不能把“看着不清楚”直接归因低分辨率 | 将请求画布、解码crop、TextureView尺寸、内容矩形、每像素缩放和系统窗口尺寸串起来。先排除非整数缩放/安全区/系统栏造成的二次缩放，再判断编码压缩、文字UI尺寸和物理显示差异。 | 保存同一静止列表/地图细文字的系统原始PNG，并拍摄同位置屏幕对照；分别比较静止和滑动。若有1:1显示条件，先验证1:1；UI大小、码率和分辨率试验每次只改一个变量。 |
| 3. 跟手 | 所有 MOVE 进入单线程无界 executor，慢发送可堆积旧坐标；每次发送还在锁内逐字节 Formatter 生成 TRACE | 合并仍在队尾、同会话同触点状态的 MOVE；DOWN/UP/CANCEL/多指变化/菜单释放和 Siri/媒体命令保持顺序。复用已有快速十六进制转换，日志字节内容不变。增加排队与发送分开统计。 | 同一列表连续滑动并穿插快速点按、松手、两指、三指菜单和Siri/媒体按键；比较同一路径 B-connect2 与输入候选，先验整包收益，不能将收益全归于某一项。若需独立归因，再准备单变量对照包（本轮未产出）。无卡住按压、丢释放、跨会话旧坐标；排队下降且用户主观改善，才保留策略。 |

下一次现场先做连接基线和诊断，再比较触控；清晰度以静止画面先验，避免把滑动时运动模糊当作像素尺寸错误。先完成三轮常规软件重连获得范围，不用少量样本计算可信的 p95；冷启动在用户方便时单独验证。最终验收仍包含用户观感和音乐无退化。

## 本轮最小实现与职责

参照当前成熟实现：`CarPlayController.sendTouch/requestSiri/sendMediaButton` 负责输入串行编排；`CarPlayHostActivity.onHostTouch` 负责 MotionEvent 语义和现有坐标映射；`AirPlaySession.sendTouch/sendCommandLocked` 负责加密发送；`airplay/Bytes.kt` 已有逐半字节十六进制转换；`NcmUsbBridge` 负责其独占连接的 USB IO；`CarPlayVideoLayout.fit` 与 Activity 的矩阵负责显示布局。

必要性：现有参数不能阻止发送队列追赶旧 MOVE，也无法从现有日志区分首次USB传输等待及显示二次缩放。因此在现有 owner 内做局部改动，不改协议、架构、模块边界或音视频配置。

1. `CarPlayInputQueue` 使用 controller 现有异步串行 executor；仅替换队尾尚未执行的 MOVE。触点 id/down 状态不一致、会话身份变化或命令插入均形成屏障；已经开始发送的事件不替换，不持有队列锁进行 socket IO。MOVE 洪泛只占当前连续序列一个待发槽；关键边沿事件不丢弃，因此不是对所有输入设置硬容量上限。
2. `sendTouch` 新增默认 false 的 move 参数。已全量检索仓库调用者：Activity 正常触摸传入 ACTION_MOVE；三指菜单和设置菜单的空列表释放继续使用默认 false。合并仅对硬件门控后的 8600 B/C stage 生效，其它 stage 保持逐事件 FIFO。Siri、媒体按钮使用同一队列屏障；执行前检查仍为原会话。
3. 每5秒有输入活动时聚合 `input queue reports/coalesced/notSent/queueMaxMs/sendMaxMs`。queueMaxMs 从进入 controller 队列到发送开始，合并后以保留的最新事件时间计算；不含 Android 事件分发前等待。sendMaxMs 包含转换、事件锁、加密和 socket 写；notSent 包括过期会话和发送拒绝，不强行归因为网络丢包。原 touch2frame 指标保持原语义。
4. AirPlay TRACE 继续保留全量相同文本，复用已有 `toHexString`，避免每字节创建 Formatter。没有删日志、截断报文或变更加密。
5. NCM 只增加首个 OUT 原始失败返回和调用耗时、首个 IN 的时间、CDC NETWORK_CONNECTION 状态变化；不新增 controlTransfer、接口复位、clear-halt、原始探针、重试或系统配置。Android 负返回仍不能当成具体 errno。
6. 显示布局只新增变化时的去重记录；不修改 TextureView 矩阵、视频尺寸、帧率、safeArea 或 UI 大小。windowMetrics 是 Android 窗口资料，不是面板原生像素的认证值。

非目标：不替换 USB/NCM/TUN 网络栈、不重新引入 C 接收队列、不开 decoder flush、不调整音乐缓冲、不承诺量产车冷启动结果。当前证据不支持直接升码率、强改4K、关闭系统栏或盲目复位USB接口。

## 本地验证与交付状态

- TDD mode off（沿既有计划）；仍覆盖输入队列的100次连续移动、事件边沿/命令屏障、会话替换、关闭、拒绝、真实阻塞工作线程和统计口径，并对全部256种字节验证日志转换一致性。
- 共享/并发改动按全局约定做独立 agent 实际差异复核；只审查本轮相对已保存文件快照的增量，保留此前 USB/授权/A/B/C 工作区内容。
- 用户已结束实车调试，本轮不能把离线检查写为“快速进入/画质/跟手已修复”。
- 最小续接：车机仍是已验收基本运行的 B-connect2；没有后台车机采集、没有安装本轮候选、没有提交/推送。本轮源文件改动前快照在 `D:/geely/output/next-optimization-20261028/baseline/`。

## 本轮候选交付结果

- APK：`D:/geely/output/next-optimization-20261028/DiPlay-8600-USB3-B-input1.apk`，13,536,859 字节。
- SHA-256：`709a667b4228ac0a469a4cf1cda42f05762aacec8acfb87b7731979ccc1787d4`；与构建输出读回一致。
- 包名仍为 `com.shihab.diplay.repair1.geely8600`；版本 `0.2.10-H53-USB3-B` / code29。候选身份以文件名和 SHA 区分，不能只看桌面图标/版本名。
- 最终构建及 shared/common 回归完成，730项：727通过、3项既有 host-JNI 测试跳过、0失败/错误。输入队列7项及十六进制日志兼容1项均通过。首轮修正了不存在的 TAG 引用后重新执行，最终日志 `build-B-input1-final.log` 为 BUILD SUCCESSFUL / 1m12s。
- `verify-geely-usb-apk.ps1` 对照 B-connect2 通过：API18、ARMv7、v1/v2签名、同包同签、helper和认证材料/默认USB过滤器。签名 SHA-256 仍为 `f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79`，日志 `verify-B-input1.log`。
- 逐 ZIP entry 内容比较：新旧均369项，仅 `classes2.dex` 及三个 JAR 签名文件变化，native库、资源、helper、认证材料均不变。`git diff --check` 通过。
- 新的独立审查 agent `/root/review_next_input` 对照本轮文件快照审查输入顺序、并发、会话隔离、关闭、API18、调用方及日志语义，未发现 Critical/Important 问题；独立读回730项测试结果。结论限离线候选交付。
- 没有装车，不能认定速度、清晰度、触控或音乐无退化已验收。下一次用户恢复现场调试后，先核对当前安装身份与参数，再执行上表验证。
