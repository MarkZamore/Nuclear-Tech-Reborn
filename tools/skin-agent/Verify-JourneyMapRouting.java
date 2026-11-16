import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import com.google.gson.JsonObject;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer;
import net.minecraftforge.fml.common.asm.transformers.SideTransformer;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import net.minecraftforge.fml.relauncher.Side;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

final class JourneyMapRoutingProbe {
    private static final String AGENT = "lanminecraft.nuclear.skins.";
    private static final String HOOK_PATH = AGENT.replace('.', '/');
    private static final String FILE_HANDLER = "journeymap/client/io/FileHandler";
    private static final String MAP_CLIENT = "journeymap/client/JourneymapClient";
    private static final String PLAYER_CONFIG = "journeymap/common/util/PlayerConfigController";
    private static final String STEAM_CLIENT = "link/e4steam/retro/forge/E4steamForge112Client";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected JourneyMap JAR, e4steam JAR, Minecraft client JAR, and fixture directory.");
        }
        require("1.8".equals(System.getProperty("java.specification.version")), "The probe must run under Java 8");
        // A lookup with null as the loader requires the shipped agent's bootstrap installation.
        ClassFileTransformer transformer = (ClassFileTransformer)
                Class.forName(AGENT + "JourneyMapWorldCompatibility", true, null).newInstance();
        File journeyMap = new File(args[0]);
        File e4steam = new File(args[1]);
        File minecraft = new File(args[2]);
        Path fixture = Paths.get(args[3]);

        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
        URL[] inputs = {journeyMap.toURI().toURL(), e4steam.toURI().toURL(), minecraft.toURI().toURL()};
        try (LaunchClassLoader loader = new LaunchClassLoader(inputs)) {
            Launch.classLoader = loader;
            Class<?> launchHandler = Class.forName("net.minecraftforge.fml.relauncher.FMLLaunchHandler");
            Field side = launchHandler.getDeclaredField("side");
            side.setAccessible(true);
            side.set(null, Side.CLIENT);
            FMLDeobfuscatingRemapper.INSTANCE.setup(
                    minecraft.getParentFile(), loader, "/deobfuscation_data-1.12.2.lzma");
            SideTransformer sideTransformer = new SideTransformer();
            DeobfuscationTransformer deobfuscation = new DeobfuscationTransformer();

            verifyClass(journeyMap, FILE_HANDLER, loader, sideTransformer, deobfuscation, transformer,
                    new Hook("getJMWorldDirForWorldId", "JourneyMapWorldRouting", "selectDirectory",
                            "(Ljava/lang/Object;Ljava/io/File;)Ljava/io/File;"));
            verifyClass(journeyMap, MAP_CLIENT, loader, sideTransformer, deobfuscation, transformer,
                    new Hook("startMapping", "JourneyMapGuestWorld", "awaitSave", "(Ljava/lang/Object;)Z"));
            verifyClass(journeyMap, PLAYER_CONFIG, loader, sideTransformer, deobfuscation, transformer,
                    new Hook("getPlayerConfig", "JourneyMapWorldRouting", "addSaveId",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V"),
                    new Hook("updateClientConfigs", "JourneyMapGuestWorld", "acceptConfig", "(Ljava/lang/Object;)V"));
            verifyClass(e4steam, STEAM_CLIENT, loader, sideTransformer, deobfuscation, transformer,
                    new Hook("connect", "JourneyMapGuestWorld", "markSteamEndpoint", "(Ljava/net/InetSocketAddress;)V"));
        }
        verifySaveIdentity(fixture);
        verifyDirectoryRouting(fixture);
    }

    private static void verifyClass(File jar, String target, LaunchClassLoader loader,
            SideTransformer side, DeobfuscationTransformer deobfuscation, ClassFileTransformer transformer,
            Hook... hooks) throws Exception {
        byte[] original;
        try (JarFile archive = new JarFile(jar)) {
            JarEntry entry = archive.getJarEntry(target + ".class");
            require(entry != null, jar.getName() + " has no " + target);
            try (InputStream stream = archive.getInputStream(entry)) {
                original = readAll(stream);
            }
        }
        String dotted = target.replace('/', '.');
        byte[] forge = deobfuscation.transform(dotted, dotted, side.transform(dotted, dotted, original));
        byte[] patched = transformer.transform(loader, target, null, null, forge);
        require(patched != null, "No transform for " + target + " after Forge reserialization " + sha256(forge));
        ClassReader reader = new ClassReader(patched);
        // ASM's dataflow verifier checks the rewritten methods without defining all other Minecraft classes.
        reader.accept(new CheckClassAdapter(new ClassWriter(0), true), ClassReader.EXPAND_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM5) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM5) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String type,
                            boolean isInterface) {
                        for (Hook hook : hooks) {
                            if (name.equals(hook.target) && owner.equals(HOOK_PATH + hook.owner)
                                    && method.equals(hook.method) && type.equals(hook.descriptor)
                                    && opcode == Opcodes.INVOKESTATIC) {
                                hook.calls++;
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (Hook hook : hooks) {
            require(hook.calls == 1, target + "." + hook.target + " expected one " + hook.method
                    + " call, saw " + hook.calls);
        }
        System.out.println("PASS Forge pipeline " + target + " input " + sha256(forge));
    }

    private static void verifySaveIdentity(Path fixture) throws Exception {
        Method getOrCreate = Class.forName(AGENT + "JourneyMapSaveIdentity", true, null)
                .getMethod("getOrCreate", File.class);
        Path first = Files.createDirectory(fixture.resolve("New World"));
        Path renamed = fixture.resolve("Renamed World");
        String firstId = saveId(getOrCreate, first);
        require(firstId.equals(saveId(getOrCreate, first)), "An existing save changed its map identity");
        Files.move(first, renamed);
        require(firstId.equals(saveId(getOrCreate, renamed)), "Renaming a save changed its map identity");
        Files.createDirectory(first);
        String replacementId = saveId(getOrCreate, first);
        require(!firstId.equals(replacementId), "A replacement with the same name inherited its old map");
        Path transferred = Files.createDirectory(fixture.resolve("Transferred World"));
        Path identityFile = Paths.get("lanminecraft-journeymap-id");
        Files.copy(renamed.resolve(identityFile), transferred.resolve(identityFile));
        require(firstId.equals(saveId(getOrCreate, transferred)), "Transferring a save changed its map identity");
        Files.write(first.resolve(identityFile), "../other-save".getBytes(StandardCharsets.US_ASCII));
        require(getOrCreate.invoke(null, first.toFile()) == null, "Malformed map identity was accepted");
        System.out.println("PASS repeated open, rename, same-name replacement, transfer and malformed save identity");
    }

    private static String saveId(Method getOrCreate, Path save) throws Exception {
        String id = (String) getOrCreate.invoke(null, save.toFile());
        require(id != null && UUID.fromString(id).toString().equals(id), "Expected a canonical UUID for " + save);
        return id;
    }

    private static void verifyDirectoryRouting(Path fixture) throws Exception {
        Class<?> routing = Class.forName(AGENT + "JourneyMapWorldRouting", true, null);
        Method select = routing.getMethod("selectDirectory", Object.class, File.class);
        Method addSave = routing.getMethod("addSaveId", Object.class, Object.class);
        File game = fixture.toFile();
        FakeClient minecraft = new FakeClient();
        // The current save has the same display and directory name as a removed save.
        Path legacy = Files.createDirectories(fixture.resolve("journeymap/data/sp/New World/waypoints"));
        Path waypoint = Files.write(legacy.resolve("old.json"), new byte[]{42});
        minecraft.server = new FakeServer(fixture.resolve("New World").toFile());
        Files.delete(fixture.resolve("New World/lanminecraft-journeymap-id"));
        File host = (File) select.invoke(null, minecraft, game);
        require(host != null && host.toPath().getParent().equals(fixture.resolve("journeymap/data/worlds")),
                "The host used an unscoped map directory");
        require(!host.toPath().startsWith(legacy.getParent()), "The host used the previous save's map");
        JsonObject settings = new JsonObject();
        JsonObject config = new JsonObject();
        config.add("settings", settings);
        addSave.invoke(null, config, new FakePlayer(minecraft.server));
        require(host.getName().equals(settings.get("lanmc_world_uuid").getAsString()),
                "Guest wire ID differs from the host directory");
        minecraft.server = new FakeServer(fixture.resolve("Renamed World").toFile());
        require(!host.equals(select.invoke(null, minecraft, game)), "Switching actual saves reused the host map");
        require(Files.readAllBytes(waypoint)[0] == 42, "The old waypoint was altered");

        minecraft.server = null;
        require(select.invoke(null, minecraft, game) == null, "An ordinary connection was intercepted");
        Method mark = Class.forName(AGENT + "JourneyMapGuestWorld", true, null)
                .getMethod("markSteamEndpoint", InetSocketAddress.class);
        InetSocketAddress endpoint = new InetSocketAddress("127.0.0.1", 31080);
        mark.invoke(null, endpoint);
        minecraft.handler = new FakeHandler(endpoint);
        minecraft.serverData = new FakeServerData("127.0.0.1:31080");
        File pending = (File) select.invoke(null, minecraft, game);
        require(pending != null && pending.toPath().getParent().equals(fixture.resolve("journeymap/data/waiting")),
                "Steam guest opened a named map before its save ID arrived");
        require(pending.equals(select.invoke(null, minecraft, game)), "One guest connection changed its pending path");
        minecraft.handler = new FakeHandler(endpoint);
        require(select.invoke(null, minecraft, game) == null, "A different connection inherited the guest path");
        System.out.println("PASS host routing and wire ID, save switch, unchanged old waypoints and guest isolation");
    }

    public static final class FakeClient {
        public Object field_71441_e = new Object();
        FakeServer server;
        FakeServerData serverData;
        FakeHandler handler;

        public static FakeClient func_71410_x() { throw new UnsupportedOperationException(); }
        public boolean func_71356_B() { return server != null; }
        public FakeServer func_71401_C() { return server; }
        public FakeServerData func_147104_D() { return serverData; }
        public FakeHandler func_147114_u() { return handler; }
        public void func_152344_a(Runnable task) { task.run(); }
    }

    public static final class FakePlayer {
        private final FakeServer server;
        FakePlayer(FakeServer server) { this.server = server; }
        public FakeServer func_184102_h() { return server; }
    }

    public static final class FakeServer {
        private final FakeSave save;
        FakeServer(File directory) { save = new FakeSave(directory); }
        public FakeSave func_71218_a(int dimension) { return dimension == 0 ? save : null; }
    }

    public static final class FakeSave {
        private final File directory;
        FakeSave(File directory) { this.directory = directory; }
        public FakeSave func_72860_G() { return this; }
        public File func_75765_b() { return directory; }
    }

    public static final class FakeServerData {
        public final String field_78845_b;
        FakeServerData(String address) { field_78845_b = address; }
    }

    public static final class FakeHandler {
        private final InetSocketAddress endpoint;
        FakeHandler(InetSocketAddress endpoint) { this.endpoint = endpoint; }
        public FakeHandler func_147298_b() { return this; }
        public InetSocketAddress func_74430_c() { return endpoint; }
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read);
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hash.append(String.format("%02x", value & 0xff));
        }
        return hash.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Hook {
        final String target;
        final String owner;
        final String method;
        final String descriptor;
        int calls;

        Hook(String target, String owner, String method, String descriptor) {
            this.target = target;
            this.owner = owner;
            this.method = method;
            this.descriptor = descriptor;
        }
    }
}
