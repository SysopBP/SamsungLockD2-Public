package app.d2lock.livehub

enum class LiveHubKind { CALL, NAVIGATION, TIMER, CHARGING, MEDIA, NOTIFICATION, WEATHER }

data class LiveHubCard(
    val id: String,
    val kind: LiveHubKind,
    val title: String,
    val subtitle: String = "",
    val progress: Float? = null,
    val priority: Int = defaultPriority(kind),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        fun defaultPriority(kind: LiveHubKind) = when (kind) {
            LiveHubKind.CALL -> 100
            LiveHubKind.NAVIGATION -> 90
            LiveHubKind.TIMER -> 80
            LiveHubKind.CHARGING -> 70
            LiveHubKind.MEDIA -> 60
            LiveHubKind.NOTIFICATION -> 50
            LiveHubKind.WEATHER -> 20
        }
    }
}
