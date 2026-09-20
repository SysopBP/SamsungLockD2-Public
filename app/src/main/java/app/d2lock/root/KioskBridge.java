package app.d2lock.root;

import android.os.Process;
import android.os.SystemClock;
import java.io.*;
import java.nio.channels.FileLock;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs in a separate root app_process. Only grants a temporary task allowlist, never credentials. */
public final class KioskBridge {
    private static final String PACKAGE = "app.d2lock";
    private static volatile long lastPing = SystemClock.elapsedRealtime();
    private static final AtomicBoolean released = new AtomicBoolean();
    private static Object tasks;
    private static Class<?> taskApi;
    // Unmanaged-device default: power menu available. No persistent DPC policy is changed.
    private static final int IDLE_FEATURES = 16;
    private static volatile boolean changed;

    private static Object invoke(Object service, Class<?> api, String name, Class<?>[] types, Object... args) throws Exception {
        return api.getMethod(name, types).invoke(service, args);
    }
    private static void packages(String[] values) throws Exception {
        invoke(tasks, taskApi, "updateLockTaskPackages", new Class<?>[]{int.class, String[].class}, 0, values);
    }
    private static void features(int flags) throws Exception {
        invoke(tasks, taskApi, "updateLockTaskFeatures", new Class<?>[]{int.class, int.class}, 0, flags);
    }
    private static synchronized void release() {
        if (released.getAndSet(true)) return;
        if (changed) {
            try {
                packages(new String[0]);
                features(IDLE_FEATURES);
                System.out.println("D2_RELEASED");
            } catch (Exception e) { System.out.println("D2_ERROR recovery failed; reboot to recover"); }
        }
        System.out.flush();
    }
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root permission required");
            // Single-user prototype: do not alter work-profile or another user's policy.
            if (args.length < 1 || args.length > 17 || !"0".equals(args[0])) throw new SecurityException("Only primary user supported");
            java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
            allowed.add(PACKAGE);
            for (int i = 1; i < args.length; i++) {
                if (!args[i].matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))
                    throw new SecurityException("Invalid phone package");
                allowed.add(args[i]);
            }
            try (RandomAccessFile file = new RandomAccessFile("/data/local/tmp/d2-kiosk-session.lock", "rw");
                 FileLock lock = file.getChannel().tryLock()) {
                if (lock == null) throw new IllegalStateException("Another D2 session is recovering");
                taskApi = Class.forName("android.app.IActivityTaskManager");
                tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                Class<?> binder = Class.forName("android.os.IBinder");
                Object dpBinder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "device_policy");
                Class<?> dpApi = Class.forName("android.app.admin.IDevicePolicyManager");
                Object dp = Class.forName("android.app.admin.IDevicePolicyManager$Stub").getMethod("asInterface", binder).invoke(null, dpBinder);
                if (invoke(dp, dpApi, "getDeviceOwnerComponent", new Class<?>[]{boolean.class}, false) != null ||
                    invoke(dp, dpApi, "getProfileOwnerAsUser", new Class<?>[]{int.class}, 0) != null)
                    throw new SecurityException("Managed devices are not supported");
                if ((Integer) invoke(tasks, taskApi, "getLockTaskModeState", new Class<?>[0]) != 0)
                    throw new IllegalStateException("Another task is already locked");
                java.lang.Process dump = new ProcessBuilder("/system/bin/dumpsys", "activity", "activities")
                    .redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                Thread drain = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(dump.getInputStream()))) {
                        String line; while ((line = reader.readLine()) != null) output.append(line).append('\n');
                    } catch (IOException ignored) { }
                });
                drain.start();
                if (!dump.waitFor(5, TimeUnit.SECONDS)) { dump.destroyForcibly(); throw new IOException("Policy inspection timed out"); }
                drain.join(1000);
                if (drain.isAlive() || dump.exitValue() != 0) throw new IOException("Policy inspection failed");
                String marker = "mLockTaskPackages (userId:packages)=";
                int offset = output.indexOf(marker);
                if (offset < 0) throw new IOException("Unsupported Android policy format");
                String section = output.substring(offset + marker.length()).split("\\n\\s*\\n", 2)[0].trim();
                for (String line : section.split("\\n")) {
                    if (!line.trim().isEmpty() && !line.trim().matches("u[0-9]+:\\[\\]"))
                        throw new SecurityException("Existing task allowlist; refusing to overwrite it");
                }
                Runtime.getRuntime().addShutdownHook(new Thread(KioskBridge::release));
                // Preserve power-menu recovery and existing keyguard behavior. Home/Recents stay off.
                changed = true;
                features(16 | 32);
                // The app resolves the selected dialer and system in-call UI for this device.
                // Keep Home/Recents disabled; answering a call must not release D2's lease.
                packages(allowed.toArray(new String[0]));
                lastPing = SystemClock.elapsedRealtime();
                Thread watchdog = new Thread(() -> {
                    while (!released.get()) {
                        SystemClock.sleep(1000);
                        if (SystemClock.elapsedRealtime() - lastPing > 20000) {
                            release(); System.exit(0);
                        }
                    }
                }, "D2-recovery");
                watchdog.setDaemon(true); watchdog.start();
                System.out.println("D2_READY"); System.out.flush();
                try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                    String command;
                    while ((command = input.readLine()) != null) {
                        if ("RELEASE".equals(command)) break;
                        if ("PING".equals(command)) lastPing = SystemClock.elapsedRealtime();
                    }
                } finally { release(); }
            }
        } catch (Throwable e) {
            release();
            Throwable reason = e.getCause() == null ? e : e.getCause();
            System.out.println("D2_ERROR " + reason.getClass().getSimpleName() + ": " + reason.getMessage());
            System.out.flush();
        }
    }
}
