# ADR 0001: 系统默认数字助手注册与悬浮小球交互机制

## 状态
已接受 (Accepted)

## 上下文 (Context)
用户希望将 RikkaHub 注册为 Android 系统默认数字助手（Default Digital Assistant），使得在系统任何界面长按电源键、从底部左右角滑动手势或点击助手硬件键时，均可在屏幕底部唤出输入栏。并在发送消息后无缝收缩为停靠在屏幕边缘的悬浮状态小球，实时指示生成进度（呼吸/旋转环），且点击可展开半屏卡片查看流式回答，并支持无缝跳转至主 App 全屏查看。

## 决策 (Decision)
1. **系统默认数字助手规范实现**:
   - 采用 Android 原生 `VoiceInteractionService` + `VoiceInteractionSessionService` + `VoiceInteractionSession` 架构注册系统级数字助手。
   - 在 `VoiceInteractionSession` 中直接承载沉浸式 Compose 浮层，当系统触发 `onShow(args, flags)` 时在屏幕底部弹出输入栏，获得输入焦点。
   - 点击遮罩或返回键时调用 `hide()` 关闭底部输入面板。
2. **悬浮小球与悬浮面板**:
   - 申请并在设置中引导授权 `SYSTEM_ALERT_WINDOW`（显示在其他应用上层权限）。
   - 实现 `AssistBubbleManager`，基于应用级/全局 WindowManager 承载状态小球与展开对话卡片。
   - 拖拽吸边：松手后平滑磁吸至屏幕最近边缘；
   - 交互闭环：底部面板发送 -> 隐藏底部面板 -> 唤起悬浮小球展示生成呼吸状态；点击小球展开悬浮对话面板；面板提供“收起为小球”、“清空对话”、“在 App 中打开”入口。
3. **独立会话沉淀**:
   - 数字助手会话绑定专有 `Conversation`，记录在 Settings/Preferences 中，不污染日常主聊天上下文，但统一持久化入 Room 数据库，用户亦可在主应用中检索和回顾。

## 影响 (Consequences)
- 优点：完美契合 Android 标准助手体验，手势调出零延迟；后台生成时悬浮小球不阻塞用户继续操作其他 App。
- 权衡：悬浮小球需要用户授予悬浮窗权限（`SYSTEM_ALERT_WINDOW`），需在设置页面和助手页面提供完善且友好的引导逻辑。
