# DiPlay Legacy 流畅度源码分析与 8600 移植建议

日期：2026-10-27。状态：静态分析完成，未移植性能代码、未构建参考项目、未采集或安装车机。

## 基线与保存位置

- 原 P16 方案保留不变：[P16 执行方案](aegis/plans/2026-10-27-p16-performance-port.md)。它仍是计划，不代表已经实现。
- 自用项目：`zyxnice/DiPlay-Geely-Android43`，`main`，`f524611e3c2f2b064c765da0e63d061d035ad51b`。
- 参考项目：`programmerguohuajing/DiPlay-Legacy-Android`。
- 本次 fetch 后源码：`bec397e2a3bbf85a4ff3128be68dd2fcc810d18f`，mobile 版本 `0.3.2` / code `122`。
- 独立下载目录：`D:\geely\analysis\DiPlay-Legacy-reference-20261027`。参考源码无本地修改。
- 车机目标：Android 4.3 / API 18；当前 USB2 已由用户确认能进入 CarPlay，但音画卡顿的现场瓶颈尚未测定。

## 结论

Legacy 的性能方案并非一个 USB 参数。主要包括原生认证解密、视频接收与解密分离、有界队列背压、减少无谓解码器重建，以及可选的时间戳平滑显示。另有实验性音乐预缓冲和用户态网络栈。

对 8600 优先研究“原生解密 + 分阶段性能统计”，随后研究视频队列和恢复。时间戳平滑显示的关键接口需要 API 21，不能直接用于 API 18；lwIP 网络替换也不应与第一批性能修改一起引入。

## 1. 真正实现了 C/JNI 解密，且覆盖音视频公共入口

固定源码：

- [AirPlayCrypto.kt](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayCrypto.kt#L133-L158)
- [NativeChaCha.kt](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/airplay/NativeChaCha.kt)
- [JNI 实现](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/jni/chacha20_poly1305_jni.c)

`AirPlayCrypto.chachaOpen()` 优先调用 native；加载或自检失败再回退平台 Cipher / BouncyCastle。`ScreenCodec.decryptFrame()` 和 `AudioStream` 都调用这个公共入口，因此不是只优化画面，也覆盖普通音频包的认证解密。

原生代码仍校验 Poly1305 标签，验证成功后才覆盖密文；不是关闭校验换速度。加载后执行 RFC 8439 向量自检。另有 `shared/src/test/native/chacha20_poly1305_test.c`，包含向量及篡改拒绝检查；本轮只检查其源码，没有执行测试。

收益机制：将低版本 Dalvik 上的密码运算移到 C。其注释中的约 0.2 MB/s、单帧约 95 ms 是作者场景描述，不是 8600 实测结果。未见此实现依赖 NEON，不能把它描述成已完成的 NEON 优化。

移植重点：

1. 核验 ARMv7 `.so` 的 API 18 可加载性和符号依赖，保留旧实现回退及路径统计。
2. 原实现解密成功会修改传入密文，再返回一份明文副本；自用版本不能未经调用方核查就改变公共入口的输入所有权语义。
3. JNI 使用 `GetPrimitiveArrayCritical`，运算期间持有数组；要测大帧耗时和 GC 停顿，不能称为完全零拷贝或绝无 GC 影响。
4. 分别测音频和视频的解密耗时、队列等待和输出；画面变顺不替代音乐验收。

这与之前 xikai P16 文档中的“未来 JNI 解密计划”不是同一状态：本次参考 Legacy 仓库已有实际 C 与 JNI 源码。

## 2. 接收与解密分线程，用短时背压减少连续丢帧

[ScreenStream.kt 固定源码](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/airplay/ScreenStream.kt#L52-L273)

当前自用版在同一接收循环内读帧、解密、回调；Legacy 由接收线程收取密文，另一个 `airplay-screen-decrypt` 工作者解密。

- 队列容量为 **10 帧**。
- 当前满队列等待为 **30 ms**，不是旧 0.2.86 发布记录的 120 ms。
- 等待期间暂停继续读取 TCP，使背压逐步传递至发送端；仍不能解决持续算力不足。
- nonce 在收到每个视频帧时分配，随帧入队。后续丢帧不会让所有后续帧的解密计数错位。
- 超时丢帧或认证失败后通知解码端参考链已缺失，重新等待关键帧。

对 8600 的代价是额外内存和排队延迟。10 帧只是数量上限，单帧读取上限为 8 MiB，不能把它当成很小的总内存上限。移植需另有总字节与最大帧龄控制，并核对配置帧/视频帧顺序、关闭与重连后的旧回调隔离。

应先确认解密吞吐是否改善，再引入队列；不要用扩大队列掩盖持续过载。

## 3. 恢复时减少反复销毁 MediaCodec

[AndroidMediaSink.kt 恢复实现](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L1549-L1575)

自用版 `recover()` 总是释放解码器；Legacy 增加 `allowRebuild`，对于超龄积压、部分输入/输出停顿保留实例，重置参考链并请求 IDR，降低重建与关键帧到达互相干扰的概率。

必须保留两个实际细节：

- 并非所有“overflow”路径都强制软恢复：当前 `VideoJob.Resync -> recover("video queue overflow")` 仍使用默认参数，是否重建还取决于是否正在等待关键帧。
- 它的核心不是 P16 方案里的 `MediaCodec.flush()`，而是选择性保留实例。不能把两种策略不加区分地叠加。

其过期阈值由自用版的 250 ms 放宽到 800 ms。这可能减少恢复和断续，但也可能留下更旧的画面，不能称为降低延迟。8600 应沿用先测后调的原则，区分积压、真实硬件故障、格式和 Surface 变化；恢复失败仍须有界重建。

当前硬解兼容分支定向规避 `OMX.atc.video.decoder`；`OMX.k3.video.decoder` 则使用原生缓冲大小。这些不能直接套到 8600 的解码器。

## 4. 真正的“Smooth video”：按手机时间戳均匀出帧

- [显示模式与初始延迟](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/common/src/main/java/com/shilapi/xcertplay/CarPlayVideoSurface.kt#L8-L40)
- [自适应延迟算法](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/media/PacingDelay.kt)
- [实际输出分支](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt#L1587-L1618)

默认关闭。启用后使用 SurfaceView，把 iPhone 帧时间映射到本机时钟，根据最近解码输出计算显示延迟，再调用带目标时间戳的 `releaseOutputBuffer`。它处理的是“解码器成批吐出画面，屏幕显示间隔不均匀”。

初始延迟为三个帧间隔加 40 ms：60 fps 时 90 ms，30 fps 时 140 ms。自适应延迟限制在 30–200 ms；这是一种用延迟换均匀性的方案，不是压缩延迟。

关键限制：当前代码只有 `realSdkInt() >= 21` 才调用时间戳输出；API 18 走立即显示分支。因此不能把该开关完整移植后就声称在你的车机获得同样的平滑效果。SurfaceView 本身可以单独评估，但不是同一效果的证明。

作者的 [SMOOTH_WIRELESS.md](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/docs/SMOOTH_WIRELESS.md) 记录了比亚迪唐、高通解码器的 USB 对照，固定 90 ms 延迟版本约从 42 fps 到 50–51 fps 的屏幕呈现结果。该结果属于特定设备和较早实现，不能当作当前自适应版本或 8600 的收益。

## 5. 音频方案不止加大接收缓存

除公共 native 解密外，还包括：

- `NativeOpusDecoder`：系统缺少可用 Opus 解码器时使用随包 libopus，主要补解码能力；不等于当前 USB 音乐卡顿原因已找到。要先确认流格式，LPCM 不需要 Opus 解码。
- 实验性 Buffered music：针对支持该协商的音乐应用提前通过 TCP 接收 AAC-LC，维持有界预缓冲和播放时钟。默认关闭；不支持的应用仍走普通流。

[BUFFERED_AUDIO.md](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/docs/BUFFERED_AUDIO.md) 说明其总量限制包含播放器预载，最多 8 MiB，并有帧数限制。它用于吸收短时供给中断，不能弥补长期带宽不足。还涉及暂停、切歌、导航压低音量及 Siri/通话时序，不宜作为 USB2 第一批修复。

## 6. lwIP 是网络兼容替代，不是已证实的提速开关

[CarPlayController.kt 选择入口](https://github.com/programmerguohuajing/DiPlay-Legacy-Android/blob/bec397e2a3bbf85a4ff3128be68dd2fcc810d18f/shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt#L3953-L3987)

Legacy 保留两条路径：`userspaceNetworkEnabled` 为 true 时使用 `LwipNcmBridge`，否则使用原 TUN 路径。运行配置字段默认 true，但用户持久设置可以改变选择，不能只凭默认值判断某车正在走哪条链路。

lwIP 在用户态处理 NCM 上的网络通信并中继到本地端口，避免依赖系统 TUN/路由兼容性。它改变网络栈和中继职责，存在额外队列、复制及 TCP 行为验证工作；没有同车对照，不能承诺比当前 TUN 更快。你的 USB2 已经连通，第一阶段继续保留现有网络链路。

## 7. Android 4.3 与集成边界

- README 仍描述 Android 4.4/API 19；当前 mobile/common/shared 的实际 `minSdk` 已为 **17**。
- 但 `shared/build.gradle` 明确指定 NDK r25c、`APP_PLATFORM=android-19`；`Application.mk` 同样为 19。
- 因此 Java 层声明允许 API 18 不等于所有 native 库均已在 API 18 验证。需检查各库动态符号、加载和 JNI 自检，不能仅按 manifest 判定可用或不可用。
- Legacy 包名、签名、认证材料和车机准备机制与当前吉利版不同。本次目标是选择性移植机制，不替换当前已验证的 USB2 安装包，不照 README 卸载。
- 当前参考源码仍有触控事件 socket 的 `TCP_NODELAY`；未发现其 ScreenStream 已采用 P16 的 512 KiB 视频接收缓冲或对应优先级设置。两套方案不能称为完全包含关系。

## 建议的实施顺序（仅建议，未执行）

1. 保留原 P16 方案及 USB2 基线。补分阶段统计：视频收包/解密/排队/解码/显示，音频收包/解密/排队/播放，分清统计窗口与物理呈现。
2. 优先验证 ARMv7 native ChaCha：固定输入对照、篡改拒绝、API 18 加载、回退和原输入语义；先测同条件下是否改善音画。
3. 再引入视频接收/解密队列，保证 nonce、配置帧顺序、容量和生命周期正确，观察延迟是否增长。
4. 对比“保留解码器等待 IDR”与 P16 有条件 flush，选择适合本机解码器的一种受控恢复策略，避免叠加重试。
5. 保留 P16 的触控 TCP_NODELAY、视频 socket 缓冲核验及可选线程调度优化，但分别对照，防止视频抢占音频。
6. 首轮不引入完整 Smooth video、lwIP 替换、海思/AutoChips 特判或实验音乐协商。若音频仍卡，再按实际流格式和耗时选择后续工作。

验收应分开记录：USB 自动连接、动态画面平稳与触控延迟、连续音乐、导航/Siri/通话中断恢复、重插和冷启动。源码存在、构建成功或作者在别的车上有效，均不能替代 8600 实车验收。
