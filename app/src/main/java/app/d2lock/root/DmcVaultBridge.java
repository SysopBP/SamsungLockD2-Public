package app.d2lock.root;

import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Short-lived UID-0 helper; never changes Android credentials or launches Download Mode. */
public final class DmcVaultBridge {
    private static final String PREFIX = "D2_DMC_V1 ";

    public static void main(String[] args) {
        boolean root = Process.myUid() == 0;
        byte[] observed = null;
        try {
            if (!root) throw new SecurityException("Root access required");
            if (args.length != 1 || !("apply".equals(args[0]) || "read".equals(args[0])))
                throw new IllegalArgumentException("Unsupported operation");
            Class<?> api = Class.forName("com.samsung.android.service.vaultkeeper.VaultKeeperManager");
            Object manager = api.getMethod("getInstance", String.class).invoke(null, "DMC");
            if (manager == null) throw new IllegalStateException("DMC vault unavailable to root backend");
            Method read = api.getMethod("read", int.class);
            Method write = api.getMethod("write", int.class, byte[].class);
            DmcVaultPolicy.Vault vault = new DmcVaultPolicy.Vault() {
                public byte[] read() throws Exception { return (byte[]) read.invoke(manager, 1); }
                public int write(byte[] data) throws Exception { return (Integer) write.invoke(manager, 1, data); }
            };
            observed = vault.read();
            DmcVaultPolicy.validate(observed);
            byte[] result = "apply".equals(args[0]) ? DmcVaultPolicy.enforce(vault) : observed;
            emit(true, true, result, DmcVaultPolicy.matchesTarget(result) ? "Targets verified" : "Targets not satisfied");
        } catch (Throwable error) {
            Throwable cause = error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
            // The initial snapshot is historical after a failed write; do not label it current.
            emit(root, false, null, cause.getClass().getSimpleName() + ": " + cause.getMessage());
            System.exit(1);
        }
    }

    private static void emit(boolean root, boolean verified, byte[] data, String detail) {
        String flags = data == null ? "unknown unknown unknown unknown" :
            data[0] + " " + data[1] + " " + data[2] + " " + (DmcVaultPolicy.downloadAllowed(data) ? "1" : "0");
        String safe = detail.replace('\n', ' ').replace('\r', ' ');
        System.out.println(PREFIX + (root ? "1" : "0") + " " + (verified ? "1" : "0") + " " + flags + " " + safe);
    }

    private DmcVaultBridge() { }
}
