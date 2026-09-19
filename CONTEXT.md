# RikkaHub Domain Glossary

## Digital Assistant (数字助手)
- **VoiceInteractionService**: Android 系统级语音/数字助手服务抽象，注册在 `AndroidManifest.xml` 中并带有 `android.permission.BIND_VOICE_INTERACTION` 权限与 `android.voice_interaction` 元数据。
- **VoiceInteractionSession**: 数字助手每次唤起手势触发时所创建的单次交互会话，拥有专属窗口，用于在屏幕底部弹出输入栏与快捷交互。
- **Assist Bubble (悬浮小球)**: 发送问题后在屏幕全局悬浮的状态指示球，具有拖拽吸边、呼吸旋转光环指示 AI 生成状态等交互特性。
- **Assist Floating Panel (悬浮对话卡片)**: 单击悬浮小球后展开的半屏卡片浮层，展示流式 Markdown 回答并提供快捷操作（清空会话、在主应用全屏打开等）。
