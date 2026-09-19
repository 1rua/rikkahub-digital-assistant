package me.rerere.rikkahub.service.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Fullscreen
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.message.ChatMessage
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs
import kotlin.uuid.Uuid

private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}

object AssistBubbleManager : KoinComponent {
    private val chatService by inject<ChatService>()
    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var isExpandedState = MutableStateFlow(false)
    private var activeConversationIdState = MutableStateFlow<Uuid?>(null)

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showBubble(context: Context, conversationId: Uuid) {
        if (!hasOverlayPermission(context)) {
            return
        }

        activeConversationIdState.value = conversationId

        if (bubbleView != null) {
            return
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val layoutParams = WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            x = 30
            y = 300
        }

        val lifecycleOwner = OverlayLifecycleOwner()
        overlayLifecycleOwner = lifecycleOwner

        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                RikkahubTheme {
                    val isExpanded by isExpandedState.collectAsState()
                    val activeConvId by activeConversationIdState.collectAsState()

                    if (activeConvId != null) {
                        BubbleHostContent(
                            conversationId = activeConvId!!,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                toggleExpand(layoutParams)
                            },
                            onDismiss = {
                                hideBubble()
                            },
                            onOpenFullApp = { convId ->
                                hideBubble()
                                openInApp(context, convId)
                            }
                        )
                    }
                }
            }
        }

        // Handle Touch dragging and magnetic snap to edge
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        composeView.setOnTouchListener { _, event ->
            if (isExpandedState.value) {
                // When expanded, let Compose handle touch interactions
                return@setOnTouchListener false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    layoutParams.x = initialX + dx
                    layoutParams.y = initialY + dy
                    wm.updateViewLayout(composeView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleExpand(layoutParams)
                    } else {
                        // Snap to nearest horizontal edge
                        val screenWidth = context.resources.displayMetrics.widthPixels
                        val targetX = if (layoutParams.x + 30 < screenWidth / 2) 20 else (screenWidth - 150)
                        layoutParams.x = targetX
                        wm.updateViewLayout(composeView, layoutParams)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(composeView, layoutParams)
            bubbleView = composeView
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleExpand(params: WindowManager.LayoutParams) {
        val current = isExpandedState.value
        val next = !current
        isExpandedState.value = next

        val wm = windowManager ?: return
        val view = bubbleView ?: return

        if (next) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            params.x = 0
            params.y = 0
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            params.x = 30
            params.y = 300
        }
        wm.updateViewLayout(view, params)
    }

    fun hideBubble() {
        val wm = windowManager
        val view = bubbleView
        if (wm != null && view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                // ignore
            }
        }
        overlayLifecycleOwner?.destroy()
        overlayLifecycleOwner = null
        bubbleView = null
        isExpandedState.value = false
        activeConversationIdState.value = null
    }

    private fun openInApp(context: Context, conversationId: Uuid) {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", conversationId.toString())
        }
        context.startActivity(intent)
    }
}

@Composable
private fun BubbleHostContent(
    conversationId: Uuid,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
    onOpenFullApp: (Uuid) -> Unit,
) {
    val chatService = AssistBubbleManager.getKoin().get<ChatService>()
    val conversationFlow = remember(conversationId) { chatService.getConversationFlow(conversationId) }
    val conversation by conversationFlow.collectAsState()
    val isGeneratingFlow = remember(conversationId) { chatService.getGenerationJobStateFlow(conversationId) }
    val generatingJob by isGeneratingFlow.collectAsState(initial = null)
    val isGenerating = generatingJob?.isActive == true

    if (!isExpanded) {
        FloatingBubblePill(
            isGenerating = isGenerating,
            onClick = onToggleExpand
        )
    } else {
        // Expanded Panel overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleExpand
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.75f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // avoid propagating click to backdrop
                    ),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Header Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = HugeIcons.Sparkles,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Digital Assistant",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val scope = rememberCoroutineScope()
                            val context = androidx.compose.ui.platform.LocalContext.current
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        AssistConversationManager.clearAssistConversation(context)
                                    }
                                }
                            ) {
                                Icon(HugeIcons.Delete02, contentDescription = "Clear")
                            }

                            IconButton(
                                onClick = { onOpenFullApp(conversationId) }
                            ) {
                                Icon(HugeIcons.Fullscreen, contentDescription = "Open App")
                            }

                            IconButton(
                                onClick = onToggleExpand
                            ) {
                                Icon(HugeIcons.Cancel01, contentDescription = "Close")
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    val listState = rememberLazyListState()
                    LaunchedEffect(conversation.currentMessages.size, isGenerating) {
                        if (conversation.currentMessages.isNotEmpty()) {
                            listState.animateScrollToItem(conversation.currentMessages.lastIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(conversation.messageNodes, key = { it.id.toString() }) { node ->
                            ChatMessage(
                                node = node,
                                onFork = {},
                                onRegenerate = {},
                                onEdit = {},
                                onShare = {},
                                onDelete = {},
                                onUpdate = {}
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingBubblePill(
    isGenerating: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_glow")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Surface(
        modifier = Modifier
            .size(54.dp)
            .scale(if (isGenerating) scale else 1f)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        shadowElevation = 8.dp,
        tonalElevation = 6.dp
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .rotate(rotation),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
            }
            Icon(
                imageVector = HugeIcons.Sparkles,
                contentDescription = "Assistant Bubble",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
