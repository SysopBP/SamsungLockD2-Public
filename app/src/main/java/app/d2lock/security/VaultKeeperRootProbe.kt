package app.d2lock.security

/**
 * Runs in a separate, explicitly launched root app_process. It performs
 * discovery ONLY: no VaultKeeper instance creation or Binder transactions.
 */
object VaultKeeperRootProbe {
    @JvmStatic fun main(args: Array<String>) {
        try {
            val cls = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
            val methods = cls.declaredMethods.filter { it.name == "getInstance" || it.name == "read" }
            println("D2_VK_CLASS=FOUND")
            methods.forEach { method ->
                val params = method.parameterTypes.joinToString(",") { it.name }
                println("D2_VK_METHOD=${method.name}($params):${method.returnType.name}")
            }
            if (methods.none { it.name == "read" }) println("D2_VK_READ=NOT_EXPOSED")
            println("D2_VK_CALLS=NONE")
        } catch (t: Throwable) {
            println("D2_VK_ERROR=${t.javaClass.simpleName}:${t.message.orEmpty().replace('\n', ' ')}")
            println("D2_VK_CALLS=NONE")
        }
    }
}
