package app.d2lock.update

/** Tags without a comparable version never trigger an automatic downgrade prompt. */
object ReleaseVersion {
    fun newer(candidate: String, installed: String): Boolean {
        fun code(value: String) = Regex("(?:^|-)v([0-9]+)$").find(value)?.groupValues?.get(1)?.toLongOrNull()
        val newCode = code(candidate)
        val oldCode = code(installed)
        if (newCode != null && oldCode != null) return newCode > oldCode
        fun semver(value: String) = Regex("^v?([0-9]+)\\.([0-9]+)\\.([0-9]+)(.*)$")
            .matchEntire(value)?.let { m -> m.groupValues.drop(1).take(3).map { it.toLongOrNull() ?: return@let null } to m.groupValues[4] }
        val next = semver(candidate) ?: return false
        val current = semver(installed) ?: return false
        for (i in 0..2) if (next.first[i] != current.first[i]) return next.first[i] > current.first[i]
        return current.second.isNotEmpty() && next.second.isEmpty()
    }
}
