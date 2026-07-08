package com.apkagent.chat

import com.apkagent.project.ProjectStore
import com.apkagent.project.ReverseProject
import com.apkagent.ui.HistoryItem
import com.apkagent.ui.SerializableChatItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ChatHistoryManager(
    private val workspace: File,
    private val projectStore: ProjectStore,
    private val json: Json
) {

    fun historyDir(project: ReverseProject?): File {
        return if (project != null) projectStore.getChatDir(project.id)
        else File(workspace, "history").apply { if (!exists()) mkdirs() }
    }

    fun loadHistoryList(project: ReverseProject?): List<HistoryItem> {
        return historyDir(project).listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(50)
            ?.mapNotNull { f -> runCatching { json.decodeFromString<HistoryItem>(f.readText()) }.getOrNull() }
            ?: emptyList()
    }

    fun loadHistory(project: ReverseProject?, id: String): List<SerializableChatItem>? {
        val chatFile = File(historyDir(project), "${id}_chat.json")
        if (!chatFile.exists()) return null
        val items = json.decodeFromString<List<SerializableChatItem>>(chatFile.readText())
        File(historyDir(project), "last_id.txt").writeText(id)
        return items
    }

    fun restoreLastConversation(project: ReverseProject?): List<SerializableChatItem>? {
        val dir = historyDir(project)
        val lastId = File(dir, "last_id.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (lastId.isBlank()) return null
        return loadHistory(project, lastId)
    }

    fun saveConversation(
        project: ReverseProject?,
        historyItem: HistoryItem,
        messages: List<SerializableChatItem>
    ) {
        val dir = historyDir(project)
        File(dir, "${historyItem.id}.json").writeText(json.encodeToString(historyItem))
        File(dir, "${historyItem.id}_chat.json").writeText(json.encodeToString(messages))
        File(dir, "last_id.txt").writeText(historyItem.id)
    }
}
