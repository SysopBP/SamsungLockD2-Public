package app.d2lock.security

/** Vendor-defined diagnostics. No assumptions about what a numeric state means. */
data class D2SecurityCoreStatus(
    val lock: Int?,
    val maintenance: Int?,
    val atInterface: Int?,
    val source: String
)

/** Pure parser; does not call Samsung services or change device security state. */
object D2SecurityCore {
    private val statusPattern = Regex(
        """(?:Available\s*\()?\s*lock\s*=\s*(-?\d+)\s*[,; ]+\s*maint\s*=\s*(-?\d+)\s*[,; ]+\s*at\s*=\s*(-?\d+)""",
        RegexOption.IGNORE_CASE
    )

    fun parseStatus(raw: String): D2SecurityCoreStatus? {
        val match = statusPattern.find(raw) ?: return null
        val lock = match.groupValues[1].toIntOrNull() ?: return null
        val maintenance = match.groupValues[2].toIntOrNull() ?: return null
        val at = match.groupValues[3].toIntOrNull() ?: return null
        return D2SecurityCoreStatus(lock, maintenance, at, "diagnostic text")
    }
}
