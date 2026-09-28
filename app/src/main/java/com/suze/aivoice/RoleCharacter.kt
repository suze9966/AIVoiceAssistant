package com.suze.aivoice

data class RoleExample(
    val user: String = "",
    val assistant: String = ""
)

data class WorldEntry(
    val id: String,
    val keys: String = "",
    val content: String = "",
    val enabled: Boolean = true
)

data class RoleChatMeta(
    val id: String,
    val characterId: String,
    val title: String = "",
    val updatedAt: Long = 0L,
    val preview: String = ""
)

/** 对标酒馆角色卡：人设 / 场景 / 示例对白 / 世界书，不做插件和养成数值。 */
data class RoleCharacter(
    val id: String,
    val name: String,
    val emoji: String = "\uD83C\uDFAD",
    val intro: String = "",
    val greeting: String = "",
    val persona: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistory: String = "",
    val userName: String = "主人",
    val tags: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val examples: List<RoleExample> = emptyList(),
    val worldEntries: List<WorldEntry> = emptyList(),
    val avatarFile: String = "",
    val creator: String = "",
    val updatedAt: Long = 0L
) {
    fun displayIntro(): String = intro.ifBlank { description.replace("\n", " ").take(80) }

    fun allGreetings(): List<String> {
        val list = mutableListOf<String>()
        greeting.trim().takeIf { it.isNotEmpty() }?.let { list.add(it) }
        alternateGreetings.map { it.trim() }.filter { it.isNotEmpty() }.forEach { g ->
            if (g !in list) list.add(g)
        }
        return list
    }
}