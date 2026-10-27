# USB2 自动恢复续接

目标：免每次手工ADB准备USB；用户本轮要求先做好APK，不便车测。
基线：d615f757f7e7fb3a77c401ea5c3a686b9bb0527a；分支codex/geely8600-usb-auto-recovery。
历史HUD分支保留，不混入。2026-10-27用户追加授权提交推送USB2代码及排查记录；目标为origin的同名USB2分支。
用户计划晚上通过手机热点和此前Termius转发安装测试；当前不操作车机、不提前记为实车通过。

证据：GEELY-8600-HANDOFF 的临时mode0/SDP/解绑snd-usb-audio成功记录；当前ADB列表无设备。
因果状态：proximate/recurrence open，今日实时状态与冷启动闭环待测，不声称唯一根因或彻底修复。
Decision: code-change；手动步骤不满足目标；职责放在USB兼容层和现有配置入口，Controller只调度。
Minimality: sufficient repair candidate；复用OEM桥/DEX、授权fd，不扩展系统持久配置，不移植无证据的单fd路径。
TDD Mode off，使用有针对性的策略、取消/回滚、原生ioctl模拟与现有USB回归；高风险独立review。

已实现：exact guard+USB2 metadata，固定运行时helper、租约取消/绝对deadline、独立rollback；
configuration2→6授权fd上仅snd-usb-audio自动恢复；普通/旁路来源均接线。
首次Kotlin增量编译报引用解析失败；关闭incremental后完整构建成功，未修改那些被报错的扩展方法。
review_usb2_recovery累计发现取消、超时rollback、旧回滚与新会话并发、角色恢复回读4项P2，均修正并复查闭合。
随后原生处理增加Linux3.0正数rebind结果、音频驱动连带释放/恢复接口，14项模拟通过，最终NDK/APK重建成功。

最终产物：D:/geely/output/DiPlay-8600-USB2/DiPlay-8600-USB2-test.apk，16,473,613字节，
SHA256=0b899d7ad3e1de89e23845123b88dfcc4dc26a33e1d9196ae660a93e9d465973。
相关JUnit最终101 tests/0失败/0错误/0跳过；其中runtime策略13项，原生mock14项另计，不混算。
v1/v2、API18、ARMv7、USB2 metadata、实际DEX/native markers、helper与generated完全相同、两项认证资产哈希均通过。
新旧本地USB1/USB2签名证书相同f2ea4c3ab5186fc4aa783c8f09c5a52a32f9fda8c4537283ca8d495e3f6c6d79。
最终native修正由review_usb2_final_native补充复查：无明确P1/P2阻断。交付APK的ARMv7库与最终stripped构建产物逐字节一致。
本轮候选修复包交付完成；随后按用户授权提交推送源码及记录。APK、认证资产、私钥和原始运行数据不纳入Git。
提交与远端SHA以Git实际回读为准，不在同一提交内预填自身SHA。
实车待测：冷启动、拔插、退出/取消、已有播放、原厂服务回切、其他应用竞争。
