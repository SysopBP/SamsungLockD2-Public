package app.d2lock.livehub

object LiveHubStore {
    private val cards = linkedMapOf<String, LiveHubCard>()
    @Volatile var onChanged: (() -> Unit)? = null

    @Synchronized fun publish(card: LiveHubCard) {
        cards[card.id] = card
        onChanged?.invoke()
    }

    @Synchronized fun remove(id: String) {
        if (cards.remove(id) != null) onChanged?.invoke()
    }

    @Synchronized fun clear() {
        if (cards.isNotEmpty()) {
            cards.clear()
            onChanged?.invoke()
        }
    }

    @Synchronized fun snapshot(limit: Int = 3): List<LiveHubCard> =
        cards.values.sortedWith(compareByDescending<LiveHubCard> { it.priority }.thenByDescending { it.updatedAt }).take(limit)
}
