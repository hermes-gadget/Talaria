package com.nousresearch.talaria.ui.navigation

sealed class TopDest(val route: String, val label: String) {
    data object Chats : TopDest("chats", "Chats")
    data object Activity : TopDest("activity", "Activity")
    data object Manage : TopDest("manage", "Manage")
    data object You : TopDest("you", "You")
}

object Routes {
    const val CONNECT = "connect"
    const val PRIVACY = "privacy"
    fun chat(resume: String? = null) =
        if (resume.isNullOrBlank()) "chat?resume=" else "chat?resume=$resume"
}
