package com.suze.aivoice

data class RoleCharacter(
    val id: String,
    val name: String,
    val emoji: String = "\uD83C\uDFAD",
    val intro: String = "",
    val greeting: String = "",
    val persona: String = ""
)
