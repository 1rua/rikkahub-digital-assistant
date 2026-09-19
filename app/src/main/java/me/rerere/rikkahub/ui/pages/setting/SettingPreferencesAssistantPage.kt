package me.rerere.rikkahub.ui.pages.setting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.HelpCircle
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.assistant.AssistBubbleManager
import me.rerere.rikkahub.service.assistant.RikkaVoiceInteractionService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus

@Composable
fun SettingPreferencesAssistantPage() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isDefaultAssistant by remember { mutableStateOf(checkIsDefaultAssistant(context)) }
    var hasOverlayPermission by remember { mutableStateOf(AssistBubbleManager.hasOverlayPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefaultAssistant = checkIsDefaultAssistant(context)
                hasOverlayPermission = AssistBubbleManager.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_assistant_service))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_assistant_service_title)) }
                ) {
                    item(
                        leadingContent = {
                            Icon(
                                imageVector = if (isDefaultAssistant) HugeIcons.CheckmarkCircle02 else HugeIcons.Sparkles,
                                contentDescription = null
                            )
                        },
                        headlineContent = {
                            Text(
                                if (isDefaultAssistant) {
                                    stringResource(R.string.setting_page_assistant_service_status_enabled)
                                } else {
                                    stringResource(R.string.setting_page_assistant_service_status_disabled)
                                }
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_assistant_service_desc))
                        },
                        trailingContent = {
                            Button(
                                onClick = {
                                    openDefaultAssistantSettings(context)
                                }
                            ) {
                                Text(stringResource(R.string.setting_page_assistant_service_set_default))
                            }
                        }
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_assistant_service_overlay_title)) }
                ) {
                    item(
                        leadingContent = {
                            Icon(
                                imageVector = if (hasOverlayPermission) HugeIcons.CheckmarkCircle02 else HugeIcons.Codesandbox,
                                contentDescription = null
                            )
                        },
                        headlineContent = {
                            Text(
                                if (hasOverlayPermission) {
                                    stringResource(R.string.setting_page_assistant_service_overlay_granted)
                                } else {
                                    stringResource(R.string.setting_page_assistant_service_overlay_grant)
                                }
                            )
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_page_assistant_service_overlay_desc))
                        },
                        trailingContent = {
                            if (!hasOverlayPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Button(
                                    onClick = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                ) {
                                    Text(stringResource(R.string.setting_page_assistant_service_overlay_grant))
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun checkIsDefaultAssistant(context: Context): Boolean {
    val setting = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
    if (setting != null) {
        val cn = ComponentName.unflattenFromString(setting)
        return cn?.packageName == context.packageName
    }
    return false
}

private fun openDefaultAssistantSettings(context: Context) {
    val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching {
        context.startActivity(intent)
    }.onFailure {
        val fallback = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(fallback) }
    }
}
