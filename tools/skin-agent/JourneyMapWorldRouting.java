package lanminecraft.nuclear.skins;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Uses the save's ID for JourneyMap data, without opening the old name-based directory. */
public final class JourneyMapWorldRouting {
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static WeakReference<Object> currentServer = new WeakReference<>(null);
    private static String currentSaveId;
    private static String waitingDirectory;

    private JourneyMapWorldRouting() { }

    public static File selectDirectory(Object minecraft, File gameDirectory) {
        if (minecraft == null || gameDirectory == null) return null;
        try {
            if (minecraft.getClass().getField("field_71441_e").get(minecraft) == null) return null;
            boolean local = Boolean.TRUE.equals(minecraft.getClass().getMethod("func_71356_B").invoke(minecraft));
            Object server = local ? minecraft.getClass().getMethod("func_71401_C").invoke(minecraft) : null;
            String key = server == null ? JourneyMapGuestWorld.directoryKey(minecraft) : hostKey(server);
            if (key == null) return null;
            if (key.startsWith("world-")) return new File(gameDirectory, "journeymap/data/worlds/" + key.substring(6));
            return new File(gameDirectory, "journeymap/data/waiting/" + key);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warn(failure);
            return null;
        }
    }

    public static void addSaveId(Object config, Object player) {
        if (config == null || player == null) return;
        try {
            Object server = player.getClass().getMethod("func_184102_h").invoke(player);
            String id = server == null ? null : saveId(server);
            if (id == null) return;
            Object settings = config.getClass().getMethod("getAsJsonObject", String.class).invoke(config, "settings");
            if (settings != null) {
                settings.getClass().getMethod("addProperty", String.class, String.class)
                        .invoke(settings, "lanmc_world_uuid", id);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            warn(failure);
        }
    }

    private static synchronized String hostKey(Object server) throws ReflectiveOperationException {
        String id = saveId(server);
        return id == null ? waitingDirectory : "world-" + id;
    }

    private static synchronized String saveId(Object server) throws ReflectiveOperationException {
        if (currentServer.get() != server) {
            currentServer = new WeakReference<>(server);
            currentSaveId = null;
            waitingDirectory = "pending-" + UUID.randomUUID();
        }
        if (currentSaveId == null) {
            Object overworld = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);
            if (overworld == null) return null;
            Object save = overworld.getClass().getMethod("func_72860_G").invoke(overworld);
            File directory = (File) save.getClass().getMethod("func_75765_b").invoke(save);
            currentSaveId = JourneyMapSaveIdentity.getOrCreate(directory);
        }
        return currentSaveId;
    }

    private static void warn(Exception failure) {
        if (WARNED.compareAndSet(false, true)) {
            System.err.println("[Nuclear Tech Reborn] JourneyMap save routing could not complete: "
                    + failure.getClass().getSimpleName());
        }
    }
}
