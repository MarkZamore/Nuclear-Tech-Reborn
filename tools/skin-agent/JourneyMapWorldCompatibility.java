package lanminecraft.nuclear.skins;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Connects the pinned JourneyMap file resolver and its existing config packet to the save ID. */
public final class JourneyMapWorldCompatibility implements ClassFileTransformer {
    private static final String FILES = "journeymap/client/io/FileHandler";
    private static final String CLIENT = "journeymap/client/JourneymapClient";
    private static final String CONFIG = "journeymap/common/util/PlayerConfigController";
    private static final String STEAM = "link/e4steam/retro/forge/E4steamForge112Client";
    private static final String ROUTING = "lanminecraft/nuclear/skins/JourneyMapWorldRouting";
    private static final String GUEST = "lanminecraft/nuclear/skins/JourneyMapGuestWorld";

    public static void register(Instrumentation instrumentation) {
        instrumentation.addTransformer(new JourneyMapWorldCompatibility());
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!FILES.equals(name) && !CLIENT.equals(name) && !CONFIG.equals(name) && !STEAM.equals(name)) return null;
        try {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            int[] hooks = {0};
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String desc, String signature,
                                                 String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                    if (FILES.equals(name) && "getJMWorldDirForWorldId".equals(method)
                            && "(Lnet/minecraft/client/Minecraft;Ljava/lang/String;)Ljava/io/File;".equals(desc)
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        hooks[0]++;
                        return directoryHook(target);
                    }
                    if (CLIENT.equals(name) && "startMapping".equals(method) && "()V".equals(desc)) {
                        hooks[0]++;
                        return mappingHook(target);
                    }
                    if (CONFIG.equals(name) && "getPlayerConfig".equals(method)
                            && "(Lnet/minecraft/entity/player/EntityPlayerMP;)Lcom/google/gson/JsonObject;".equals(desc)) {
                        return new MethodVisitor(Opcodes.ASM5, target) {
                            @Override public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    hooks[0]++;
                                    visitInsn(Opcodes.DUP);
                                    visitVarInsn(Opcodes.ALOAD, 1);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, ROUTING, "addSaveId",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (CONFIG.equals(name) && "updateClientConfigs".equals(method)
                            && "(Ljourneymap/common/network/impl/Response;)V".equals(desc)) {
                        hooks[0]++;
                        return beforeCall(target, "acceptConfig", "(Ljava/lang/Object;)V");
                    }
                    if (STEAM.equals(name) && "connect".equals(method)
                            && "(Ljava/net/InetSocketAddress;Ljava/lang/String;)V".equals(desc)) {
                        hooks[0]++;
                        return beforeCall(target, "markSteamEndpoint", "(Ljava/net/InetSocketAddress;)V");
                    }
                    return target;
                }
            }, 0);
            if (hooks[0] == (CONFIG.equals(name) ? 2 : 1)) return writer.toByteArray();
        } catch (RuntimeException failure) {
            System.err.println("[Nuclear Tech Reborn] JourneyMap hook failed in " + name + ": " + failure);
            return null;
        }
        System.err.println("[Nuclear Tech Reborn] JourneyMap hook skipped for unexpected bytecode in " + name);
        return null;
    }

    private static MethodVisitor directoryHook(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM5, target) {
            @Override public void visitCode() {
                super.visitCode();
                Label original = new Label();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitFieldInsn(Opcodes.GETSTATIC, FILES, "MinecraftDirectory", "Ljava/io/File;");
                visitMethodInsn(Opcodes.INVOKESTATIC, ROUTING, "selectDirectory",
                        "(Ljava/lang/Object;Ljava/io/File;)Ljava/io/File;", false);
                visitInsn(Opcodes.DUP);
                visitJumpInsn(Opcodes.IFNULL, original);
                visitInsn(Opcodes.ARETURN);
                visitLabel(original);
                visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/io/File"});
                visitInsn(Opcodes.POP);
            }
        };
    }

    private static MethodVisitor mappingHook(MethodVisitor target) {
        return new MethodVisitor(Opcodes.ASM5, target) {
            @Override public void visitCode() {
                super.visitCode();
                Label original = new Label();
                visitVarInsn(Opcodes.ALOAD, 0);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUEST, "awaitSave", "(Ljava/lang/Object;)Z", false);
                visitJumpInsn(Opcodes.IFEQ, original);
                visitInsn(Opcodes.RETURN);
                visitLabel(original);
                visitFrame(Opcodes.F_SAME, 0, null, 0, null);
            }
        };
    }

    private static MethodVisitor beforeCall(MethodVisitor target, String method, String descriptor) {
        return new MethodVisitor(Opcodes.ASM5, target) {
            @Override public void visitCode() {
                super.visitCode();
                visitVarInsn(Opcodes.ALOAD, 1);
                visitMethodInsn(Opcodes.INVOKESTATIC, GUEST, method, descriptor, false);
            }
        };
    }
}
