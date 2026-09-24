package app.d2lock.livehub

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveHubCardTest {
    @Test fun urgentStatesSortAheadOfAmbientStates() {
        val cards = listOf(
            LiveHubCard("weather", LiveHubKind.WEATHER, "72°"),
            LiveHubCard("media", LiveHubKind.MEDIA, "Song"),
            LiveHubCard("call", LiveHubKind.CALL, "Incoming call")
        ).sortedByDescending { it.priority }
        assertEquals(listOf(LiveHubKind.CALL, LiveHubKind.MEDIA, LiveHubKind.WEATHER), cards.map { it.kind })
    }
}
