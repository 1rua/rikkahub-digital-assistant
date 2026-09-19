package me.rerere.rikkahub.service.assistant

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private val Context.assistPrefs by preferencesDataStore(name = "digital_assistant_prefs")

object AssistConversationManager : KoinComponent {
    private val KEY_ASSIST_CONV_ID = stringPreferencesKey("assist_conversation_id")
    private val conversationRepo by inject<ConversationRepository>()
    private val settingsStore by inject<SettingsStore>()

    suspend fun getOrCreateAssistConversationId(context: Context): Uuid {
        val prefs = context.assistPrefs.data.first()
        val storedIdStr = prefs[KEY_ASSIST_CONV_ID]
        if (!storedIdStr.isNullOrBlank()) {
            val parsedId = runCatching { Uuid.parse(storedIdStr) }.getOrNull()
            if (parsedId != null && conversationRepo.existsConversationById(parsedId)) {
                return parsedId
            }
        }

        // Create new dedicated assistant conversation
        val currentAssistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        val newId = Uuid.random()
        val newConv = Conversation(
            id = newId,
            assistantId = currentAssistant.id,
            title = "Digital Assistant",
            messageNodes = emptyList(),
        )
        conversationRepo.insertConversation(newConv)

        context.assistPrefs.edit { p ->
            p[KEY_ASSIST_CONV_ID] = newId.toString()
        }
        return newId
    }

    suspend fun clearAssistConversation(context: Context): Uuid {
        val prefs = context.assistPrefs.data.first()
        val storedIdStr = prefs[KEY_ASSIST_CONV_ID]
        if (!storedIdStr.isNullOrBlank()) {
            val parsedId = runCatching { Uuid.parse(storedIdStr) }.getOrNull()
            if (parsedId != null) {
                conversationRepo.getConversationById(parsedId)?.let {
                    conversationRepo.deleteConversation(it)
                }
            }
        }
        val currentAssistant = settingsStore.settingsFlow.value.getCurrentAssistant()
        val newId = Uuid.random()
        val newConv = Conversation(
            id = newId,
            assistantId = currentAssistant.id,
            title = "Digital Assistant",
            messageNodes = emptyList(),
        )
        conversationRepo.insertConversation(newConv)

        context.assistPrefs.edit { p ->
            p[KEY_ASSIST_CONV_ID] = newId.toString()
        }
        return newId
    }
}
