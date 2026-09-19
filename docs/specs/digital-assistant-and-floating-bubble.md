# 需求规格说明书 (Spec): 系统默认数字助手与悬浮小球交互

## 1. 概述与目标 (Overview & Objectives)
允许 RikkaHub 注册为 Android 系统默认数字助手（Default Digital Assistant）。当用户通过系统级交互手势（长按电源键、底部角滑、专用助手硬件键）唤起助手时，能够在屏幕底部快速弹出半透明输入面板。用户输入并发送问题后，面板自动收缩为停靠在屏幕边缘的悬浮状态小球，实时指示流式生成进度。用户可在任何界面点击小球展开悬浮对话面板查看回复，或一键跳转至主 App 深入对话。

---

## 2. 核心功能与用例 (Functional Requirements)

### 2.1 系统数字助手注册 (System Assistant Registration)
- **FR-1.1**: 应用必须实现 Android 标准 `VoiceInteractionService` 体系（含 SessionService、Session 及必要的 RecognitionService 占位声明）。
- **FR-1.2**: 在 `AndroidManifest.xml` 中声明 `android.permission.BIND_VOICE_INTERACTION` 权限与 `@xml/voice_interaction_service` 元数据。
- **FR-1.3**: 支持 Android 系统在“默认应用 -> 数字助手应用”中将 RikkaHub 选为默认助手。

### 2.2 底部输入栏会话唤起 (Bottom Input Sheet)
- **FR-2.1**: 系统触发数字助手手势时，触发 `VoiceInteractionSession.onShow`，在当前活跃屏幕底部以半透明遮罩与卡片的形式弹出输入面板。
- **FR-2.2**: 自动获取输入框焦点并唤起软键盘（`SOFT_INPUT_ADJUST_RESIZE` 配合 Compose `imePadding`）。
- **FR-2.3**: 点击外部空白遮罩或返回键时，调用 `hide()` 关闭底部输入面板，不打扰当前前台应用。
- **FR-2.4**: 输入文字后点击发送按钮，调用 `ChatService.sendMessage` 发起对话生成，立即收起底部输入面板，并唤起悬浮小球。

### 2.3 全局悬浮小球与动效 (Assist Floating Bubble)
- **FR-3.1 权限保障**: 依赖 `android.permission.SYSTEM_ALERT_WINDOW`（显示在其他应用上层），未授权时优雅降级并引导用户授权。
- **FR-3.2 随手拖拽与磁吸吸边**:
  - 用户可在屏幕上任意拖拽小球位置。
  - 手指松开（`ACTION_UP`）后，根据当前屏幕 X 坐标自动磁吸平滑贴靠到左侧或右侧边缘，避免遮挡屏幕中心内容。
- **FR-3.3 生成状态呼吸动效**:
  - 实时监听 `ChatService` 中该会话的生成状态 (`generationJob?.isActive`)。
  - 在生成中：小球外圈呈现动态渐变呼吸与旋转进度环（`CircularProgressIndicator` + `infiniteRepeatable` 旋转/缩放）。
  - 生成结束：停止旋转与呼吸动效，保留常驻状态指示。
- **FR-3.4 点击展开/收起**:
  - 单击悬浮小球，从小球停靠位置平滑展开为悬浮对话窗口（半屏遮罩卡片）。
  - 再次点击关闭按钮或点击半透明背景，收缩还原为小球。

### 2.4 悬浮对话面板 (Assist Floating Panel)
- **FR-4.1 消息流展示**:
  - 基于 Compose `LazyColumn` 渲染消息流，支持实时流式 Markdown 渲染、思考过程、代码块。
  - 新消息生成或追加时自动平滑滚动到底部。
- **FR-4.2 操作栏控制**:
  - **清空会话**: 一键重置清空当前助手会话记录，恢复空白初始状态。
  - **在 App 中打开**: 通过 Intent/DeepLink 携带 `conversation_id` 唤起主 Activity (`RouteActivity`)，无缝切换到全屏完整应用模式。
  - **关闭面板**: 收起面板恢复悬浮小球。

### 2.5 独立会话与设置管理 (Conversation & Settings Management)
- **FR-5.1 独立会话隔离**:
  - 数字助手采用专有独立的 `Conversation`（通过 DataStore 持久化保存其 UUID），不污染主界面的常规对话列表与上下文。
  - 助手发起的对话记录同样持久化入 Room 数据库，用户亦可在主应用中回顾历史。
- **FR-5.2 设置与引导页**:
  - 在“设置 -> 首选项 (`SettingPreferencesPage`)”中增加“系统数字助手”配置子项。
  - 自动检测并显示当前是否为默认数字助手状态；提供一键跳转系统设置引导（`ACTION_VOICE_INPUT_SETTINGS` / `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`）。
  - 自动检测并显示悬浮窗权限状态；未开启时提供一键跳转系统授权引导（`ACTION_MANAGE_OVERLAY_PERMISSION`）。

---

## 3. 非功能性需求 (Non-Functional Requirements)
- **NFR-1 性能与响应时延**: 底部输入栏必须做到即时弹出（<= 150ms），输入无卡顿。
- **NFR-2 内存与生命周期隔离**: 悬浮窗 View 必须具备合规的独立 `LifecycleOwner`、`ViewModelStoreOwner` 和 `SavedStateRegistryOwner`，在悬浮窗移除时及时 `destroy()` 释放所有视图与协程资源，不造成内存泄漏。
- **NFR-3 兼容性与国际化**:
  - 适配 Android 8.0 (API 26) 至 Android 15/16+ (API 35/37)。
  - 多语言字符串统一遵循 `setting_page_assistant_service_*` 前缀，覆盖中文与英文资源。

---

## 4. 验收标准与测试用例 (Acceptance Criteria & Test Cases)

| 编号 | 验证场景 | 预期结果 |
| :--- | :--- | :--- |
| **TC-01** | 系统默认助手设置 | 打开系统“默认数字助手应用”，列表中出现 RikkaHub，选择后系统设置生效。 |
| **TC-02** | 系统手势唤起输入栏 | 在桌面或任意第三方 App 中长按电源键/角滑，屏幕底部弹出 RikkaHub 输入栏并自动弹出软键盘。 |
| **TC-03** | 发送与缩为小球 | 输入文字点击发送，输入栏关闭，屏幕边缘出现带有旋转光环的悬浮小球，后台保持流式生成。 |
| **TC-04** | 悬浮球拖拽与磁吸 | 手指拖拽小球任意移动，松手后自动平滑吸附到左侧或右侧边缘。 |
| **TC-05** | 点击小球展开对话卡片 | 单击小球弹出半屏对话卡片，实时看到 AI 逐字输出的流式回答。 |
| **TC-06** | 跳转全屏主应用 | 点击卡片顶部的全屏按钮，悬浮窗关闭并打开 RikkaHub 完整应用，直达该会话。 |
| **TC-07** | 权限检测与引导 | 在未授予悬浮窗权限时，点击设置页对应按钮可直达系统悬浮窗权限开关页面。 |
