package app.d2lock.security

import android.os.IBinder

/**
 * Read-only Samsung service diagnostics in an isolated root app_process.
 * Does not invoke VaultKeeper read/write APIs or modify security state.
 */
object VaultKeeperRootProbe {
    private fun error(t: Throwable): String =
        "${t.javaClass.simpleName}:${t.message.orEmpty().replace('\n', ' ').take(240)}"

    @JvmStatic fun main(args: Array<String>) {
        try {
            val cls = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager")
            println("D2_VK_CLASS=FOUND")
            cls.declaredMethods.filter { it.name == "getInstance" || it.name == "read" }
                .forEach { method ->
                    val params = method.parameterTypes.joinToString(",") { it.name }
                    println("D2_VK_METHOD=${method.name}($params):${method.returnType.name}")
                }
        } catch (t: Throwable) {
            println("D2_VK_CLASS_ERROR=${error(t)}")
        }

        try {
            val sm = Class.forName("android.os.ServiceManager")
            val check = sm.getDeclaredMethod("checkService", String::class.java)
            for (name in listOf(
                "VaultKeeperService",
                "vendor.samsung.hardware.security.vaultkeeper.ISehVaultKeeper/default"
            )) {
                val label = if (name == "VaultKeeperService") "FRAMEWORK" else "VENDOR"
                try {
                    val binder = check.invoke(null, name) as? IBinder
                    println("D2_VK_${label}_BINDER=${if (binder == null) "NOT_FOUND" else "FOUND"}")
                    if (binder != null) {
                        println("D2_VK_${label}_ALIVE=${binder.isBinderAlive}")
                        try {
                            println("D2_VK_${label}_DESCRIPTOR=${binder.interfaceDescriptor}")
                        } catch (t: Throwable) {
                            println("D2_VK_${label}_DESCRIPTOR_ERROR=${error(t)}")
                        }
                    }
                } catch (t: Throwable) {
                    println("D2_VK_${label}_ERROR=${error(t.cause ?: t)}")
                }
            }
        } catch (t: Throwable) {
            println("D2_VK_SERVICE_MANAGER_ERROR=${error(t.cause ?: t)}")
        }
        println("D2_VK_API_READ=NOT_ATTEMPTED")
        println("D2_VK_FLAGS=UNCHANGED")
    }
}
