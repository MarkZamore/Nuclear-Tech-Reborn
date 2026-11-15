package lanminecraft.nuclear.skins;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/** Makes pack client hooks available to Forge's isolated class loader. */
public final class NuclearSkinAgent {
    private NuclearSkinAgent() { }

    public static void premain(String arguments, Instrumentation instrumentation) {
        try {
            File agent = new File(NuclearSkinAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agent, true));
            if (System.getProperty("minecraft.portable.skin.registry") != null) {
                LegacySkinCompatibility.register(instrumentation);
            }
            NuclearBookLocalization.register(instrumentation, agent.toPath().getParent().getParent());
        } catch (Exception | LinkageError failure) {
            System.err.println("[Nuclear Tech Reborn] Client agent could not initialize: "
                    + failure.getClass().getSimpleName());
        }
    }
}
