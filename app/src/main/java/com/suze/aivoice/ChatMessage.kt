package com.suze.aivoice

data class ChatMessage(
    val role: String,   // "user" | "assistant" | "system"
    val content: String,
    val isMe: Boolean
)
