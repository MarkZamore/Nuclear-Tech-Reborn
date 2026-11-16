package lanminecraft.nuclear.skins;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.ProtectionDomain;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Label;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;

/** Loads launcher-registered skins through the pinned Java 8 session service. */
public final class LegacySkinCompatibility implements ClassFileTransformer {
    private static final String SESSION = "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService";
    private static final String SESSION_SHA256 = "aa73c7c67dd321b50cf4f0c148896af500e53668cac28d74d963af1188854ba7";
    // Forge 14.23.5.2859's SideTransformer reserializes this class with ASM.
    // The reviewed class has the same instructions and texture checks.
    private static final String FORGE_SESSION_SHA256 = "e2519fbdb0a274214cb0755e05250f82b84d1cd4882a921e690cc52e5e2bf1ff";
    private static final String SKINS = "lanminecraft/nuclear/skins/LegacySkinProfiles";

    private LegacySkinCompatibility() { }

    public static void register(Instrumentation instrumentation) {
        instrumentation.addTransformer(new LegacySkinCompatibility());
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!SESSION.equals(name)) return null;
        String fingerprint = sha256(bytes);
        if (!SESSION_SHA256.equals(fingerprint) && !FORGE_SESSION_SHA256.equals(fingerprint)) {
            System.err.println("[Nuclear Tech Reborn] Skin compatibility skipped for unreviewed Authlib.");
            return null;
        }

        final int[] hooks = new int[1];
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                MethodVisitor target = super.visitMethod(access, method, desc, signature, exceptions);
                if ("getTextures".equals(method) && "(Lcom/mojang/authlib/GameProfile;Z)Ljava/util/Map;".equals(desc)
                        && (access & Opcodes.ACC_STATIC) == 0) {
                    hooks[0]++;
                    return new MethodVisitor(Opcodes.ASM5, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label original = new Label();
                            visitVarInsn(Opcodes.ALOAD, 1);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "applyRegisteredSkin", "(Ljava/lang/Object;)Z", false);
                            visitJumpInsn(Opcodes.IFEQ, original);
                            visitInsn(Opcodes.ICONST_0);
                            visitVarInsn(Opcodes.ISTORE, 2);
                            visitLabel(original);
                            visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
                if ("isWhitelistedDomain".equals(method) && "(Ljava/lang/String;)Z".equals(desc)
                        && (access & Opcodes.ACC_STATIC) != 0) {
                    hooks[0]++;
                    return new MethodVisitor(Opcodes.ASM5, target) {
                        @Override public void visitCode() {
                            super.visitCode();
                            Label original = new Label();
                            visitVarInsn(Opcodes.ALOAD, 0);
                            visitMethodInsn(Opcodes.INVOKESTATIC, SKINS, "isRegisteredUrl", "(Ljava/lang/String;)Z", false);
                            visitJumpInsn(Opcodes.IFEQ, original);
                            visitInsn(Opcodes.ICONST_1);
                            visitInsn(Opcodes.IRETURN);
                            visitLabel(original);
                            visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }
                return target;
            }
        }, 0);
        if (hooks[0] != 2) return null;
        System.out.println("[Nuclear Tech Reborn] Installed launcher skins for Authlib 1.5.25.");
        return writer.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
