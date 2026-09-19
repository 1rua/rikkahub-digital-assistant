# 修复计划: 系统数字助手唤出无响应与进入安全模式问题

## 1. 问题背景与现象描述 (Issue Background)
在 RikkaHub 中，当用户通过设置将应用设为系统默认数字助手（Default Digital Assistant）后：
1. **唤出无响应**：使用系统交互手势（长按电源键、长按 Home 键或角滑）唤出数字助手时，屏幕没有任何界面弹出，助手直接消失。
2. **随后进入安全模式**：再次点击桌面图标打开 RikkaHub 主应用时，应用未正常进入主界面，而是直接崩溃转入 `SafeModeActivity`（安全模式）。

---

## 2. 根因分析 (Root Cause Analysis)

### 2.1 唤出无响应根因：缺少 ViewTree Owners 导致 ComposeView 初始化抛出致命异常
- **核心定位**：`RikkaVoiceInteractionSession` 继承自 Android 系统的 `VoiceInteractionSession`。
- `VoiceInteractionSession` 仅实现了 `KeyEvent.Callback` 和 `ComponentCallbacks2`，并不是 Jetpack 的 `LifecycleOwner`、`ViewModelStoreOwner` 或 `SavedStateRegistryOwner`。
- 在 `RikkaVoiceInteractionSession.onCreateContentView()` 中直接创建并返回了 `ComposeView`：
  ```kotlin
  override fun onCreateContentView(): View {
      return ComposeView(context).apply {
          setContent { SessionBottomBar(...) }
      }
  }
  ```
- Jetpack Compose 的 `ComposeView` 在 attach 到 Window 时，必须在其 View 树上寻找到：
  1. `ViewTreeLifecycleOwner`
  2. `ViewTreeViewModelStoreOwner`
  3. `ViewTreeSavedStateRegistryOwner`
- 由于 Session 窗口未挂载上述任何 Provider，Compose 初始化时直接抛出致命未捕获异常：
  ```text
  java.lang.IllegalStateException: A ViewTreeLifecycleOwner is not found in this View and its ancestors
  ```
- 导致当前 Session 窗口瞬间崩溃关闭，用户视觉上感知为“唤出完全没有响应”。

### 2.2 进入安全模式根因：全局 CrashHandler 记录了未捕获异常
- `RikkaHubApp.kt` 初始化时通过 `CrashHandler.install(this)` 安装了默认未捕获异常监听器：
  - 该监听器在捕获到任何未处理崩溃时，会将 `crashed = true` 和崩溃调用栈以 `commit = true` 方式同步写入 `SharedPreferences ("crash_handler")`。
- `RikkaVoiceInteractionSessionService` 与主 App 运行在同一应用进程中。
- Session 崩溃触发了 `CrashHandler`，标记了崩溃状态。
- 用户随后启动 `RouteActivity` 时，`RouteActivity.onCreate()` 检查：
  ```kotlin
  if (CrashHandler.hasCrashed(this)) {
      startActivity(Intent(this, SafeModeActivity::class.java))
      finish()
      return
  }
  ```
  直接将用户拦截并导向 `SafeModeActivity`。

### 2.3 伴随缺陷分析
1. **会话 ID 传参不一致 (Key Mismatch)**：
   - `AssistBubbleManager.kt` 中打开主应用传递的 extra key 为 `"conversation_id"`：
     ```kotlin
     putExtra("conversation_id", conversationId.toString())
     ```
   - 而 `RouteActivity.kt` 中解析的 key 为驼峰形式 `"conversationId"`：
     ```kotlin
     intent.getStringExtra("conversationId")?.let { Screen.Chat(it) }
     ```
   - 导致点击悬浮卡片的全屏按钮后，无法精准导航到助手对应的会话中。
2. **Session 生命周期未与系统交互对齐**：
   - Session 在 `onShow`、`onHide`、`onDestroy` 时没有驱动生命周期状态机，容易发生内存泄漏或状态未能及时持久化。

---

## 3. 详细修复方案 (Implementation Steps)

### 步骤 1：为 `RikkaVoiceInteractionSession` 注入完整的 Lifecycle/ViewModel/SavedState 基础设施
- 参考 `AssistBubbleManager` 中的设计，在 `RikkaVoiceInteractionSession.kt` 中实现/复用 `SessionLifecycleOwner`：
  ```kotlin
  private class SessionLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
      private val lifecycleRegistry = LifecycleRegistry(this)
      private val store = ViewModelStore()
      private val savedStateRegistryController = SavedStateRegistryController.create(this)

      init {
          savedStateRegistryController.performRestore(null)
          lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
      }

      override val lifecycle: Lifecycle get() = lifecycleRegistry
      override val viewModelStore: ViewModelStore get() = store
      override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

      fun handleLifecycleEvent(event: Lifecycle.Event) {
          lifecycleRegistry.handleLifecycleEvent(event)
      }

      fun destroy() {
          lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
          store.clear()
      }
  }
  ```
- 在 `RikkaVoiceInteractionSession` 中：
  - 初始化 `sessionLifecycleOwner`；
  - 重写生命周期回调联动：
    - `onCreate()`: 调用 `sessionLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)`
    - `onShow()`: 调用 `sessionLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)` 与 `ON_RESUME`
    - `onHide()`: 调用 `sessionLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)` 与 `ON_STOP`
    - `onDestroy()`: 调用 `sessionLifecycleOwner.destroy()`
  - 在 `onCreateContentView()` 中为 `ComposeView` 绑定 ViewTree Owners 并指定销毁策略：
    ```kotlin
    return ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setViewTreeLifecycleOwner(sessionLifecycleOwner)
        setViewTreeViewModelStoreOwner(sessionLifecycleOwner)
        setViewTreeSavedStateRegistryOwner(sessionLifecycleOwner)
        setContent { ... }
    }
    ```

### 步骤 2：对齐 Intent 会话 ID 传参
- 在 `RouteActivity.kt` 中兼容解析两种常见 key 命名：
  ```kotlin
  val convId = intent.getStringExtra("conversationId") ?: intent.getStringExtra("conversation_id")
  convId?.let { Screen.Chat(it) }
  ```
- 确保从悬浮小球点击“全屏打开”后能无缝直达该助手对话。

### 步骤 3：增强容错与异常兜底
- 在 `RikkaVoiceInteractionSession.onCreateContentView()` 与 `onShow()` 逻辑外层增加 `runCatching` 兜底保护，记录错误日志，并在异常时优雅降级并关闭 Session（`hide()`），防止未捕获异常抛出至系统层并被 `CrashHandler` 记录。

---

## 4. 验证方式与测试用例 (Verification & Acceptance)

| 编号 | 测试场景 | 操作步骤 | 预期结果 |
| :--- | :--- | :--- | :--- |
| **TC-01** | 助手手势唤起测试 | 1. 确保在系统设置中将 RikkaHub 选为“默认数字助手应用”<br>2. 在任意应用或桌面，长按电源键 / Home 键 / 角滑唤起助手 | 屏幕底部正常弹出半透明输入面板，输入框自动获得焦点并唤出软键盘，没有任何闪退现象。 |
| **TC-02** | 主 App 启动安全模式回归 | 1. 执行 TC-01 唤出助手，然后点击外部空白区域或返回键取消<br>2. 点击应用图标直接启动 RikkaHub 主程序 | 应用正常打开主界面（对话列表或上一次对话），**不会**进入 `SafeModeActivity`。 |
| **TC-03** | 消息发送与悬浮球联动 | 1. 唤出助手输入栏，输入问题并点击发送 | 输入面板收起，屏幕边缘生成带有旋转动画的悬浮小球，并在后台正常流式生成 AI 回复。 |
| **TC-04** | 全屏跳转功能对齐 | 1. 单击悬浮小球展开对话卡片<br>2. 点击卡片顶部的全屏按钮 | 悬浮窗关闭，拉起 RikkaHub 并精准跳转至当前的数字助手会话中。 |
| **TC-05** | 自动化与编译测试 | 执行 `./gradlew compileDebugKotlin` 和 `./gradlew test` | 编译无警告或错误，测试用例全部通过。 |
