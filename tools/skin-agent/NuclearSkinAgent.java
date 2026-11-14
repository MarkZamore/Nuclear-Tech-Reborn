package lanminecraft.nuclear.skins;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/** Makes launcher texture hooks available to Forge's isolated class loader. */
public final class NuclearSkinAgent {
    private NuclearSkinAgent() { }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (System.getProperty("minecraft.portable.skin.registry") == null) return;
        try {
            File agent = new File(NuclearSkinAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agent, true));
            LegacySkinCompatibility.register(instrumentation);
        } catch (Exception | LinkageError failure) {
            System.err.println("[Nuclear Tech Reborn] Launcher skin agent could not initialize: "
                    + failure.getClass().getSimpleName());
        }
    }
}
