package lanminecraft.nuclear.skins;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassVisitor;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.MethodVisitor;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;

/** Translates only the client presentation of the two books. */
public final class NuclearBookLocalization implements ClassFileTransformer {
    private static final String HOOK = "lanminecraft/nuclear/skins/NuclearBookLocalization";
    private static final String QUEST = "betterquesting/api2/utils/QuestTranslation";
    private static final String NOTE = "mcjty/theoneprobe/gui/GuiNote";
    private static final String ITEM = "mcjty/theoneprobe/items/ProbeNote";
    private static final String NOTE_TITLE = "The One Probe Read Me";
    private static final String QUEST_DESCRIPTOR = "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";
    private static final String DISPLAY_NAME = "func_77653_i";
    private static final String DISPLAY_DESCRIPTOR = "(Lnet/minecraft/item/ItemStack;)Ljava/lang/String;";
    private static final String TRANSLATE_PROBE = "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/String;";
    private static NuclearBookLocalization installed;

    private final Map<String, String> quests = new HashMap<>();
    private final Map<String, String> probe = new HashMap<>();

    private NuclearBookLocalization(Path dictionary) throws IOException {
        if (Files.size(dictionary) > 4L * 1024 * 1024) throw new IOException("Oversized book dictionary");
        try (BufferedReader reader = Files.newBufferedReader(dictionary, StandardCharsets.UTF_8)) {
            if (!"NTR-RU-1".equals(reader.readLine())) throw new IOException("Invalid book dictionary version");
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\t", -1);
                if (fields.length != 3 || !("q".equals(fields[0]) || "p".equals(fields[0]))) {
                    throw new IOException("Malformed book dictionary entry");
                }
                String source = decode(fields[1]);
                String translated = decode(fields[2]);
                Map<String, String> entries = "q".equals(fields[0]) ? quests : probe;
                if (source.isEmpty() || translated.isEmpty() || entries.put(source, translated) != null) {
                    throw new IOException("Empty or duplicate book dictionary entry");
                }
            }
        }
        if (quests.isEmpty() || !probe.containsKey(NOTE_TITLE)) throw new IOException("Incomplete book dictionary");
    }

    private static String decode(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    public static void register(Instrumentation instrumentation, Path pack) {
        Path dictionary = pack.resolve("config/lanminecraft/nuclear-book-ru.tsv");
        if (!Files.isRegularFile(dictionary)) return;
        try {
            installed = new NuclearBookLocalization(dictionary);
            instrumentation.addTransformer(installed);
        } catch (IOException | RuntimeException failure) {
            System.err.println("[Nuclear Tech Reborn] Could not load the Russian book dictionary: " + failure);
        }
    }

    public static String translateQuest(String rendered, String original, Class<?> context) {
        if (original == null || !original.equals(rendered)) return rendered;
        String translated = installed.quests.get(original);
        return translated != null && isRussian(context) ? translated : rendered;
    }

    public static String translateProbe(String original, Class<?> context) {
        String translated = installed.probe.get(original);
        return translated != null && isRussian(context) ? translated : original;
    }

    private static final ClassValue<ClientLanguage> LANGUAGES = new ClassValue<ClientLanguage>() {
        @Override
        protected ClientLanguage computeValue(Class<?> context) {
            try {
                return new ClientLanguage(context.getClassLoader());
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return new ClientLanguage();
            }
        }
    };

    private static boolean isRussian(Class<?> context) {
        return LANGUAGES.get(context).isRussian();
    }

    private static final class ClientLanguage {
        private final Method client, manager, current, code;

        ClientLanguage() {
            client = manager = current = code = null;
        }

        ClientLanguage(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            client = minecraft.getMethod("func_71410_x");
            manager = minecraft.getMethod("func_135016_M");
            current = manager.getReturnType().getMethod("func_135041_c");
            code = current.getReturnType().getMethod("func_135034_a");
        }

        boolean isRussian() {
            if (client == null) return false;
            try {
                Object game = client.invoke(null);
                if (game == null) return false;
                Object languages = manager.invoke(game);
                if (languages == null) return false;
                Object selected = current.invoke(languages);
                return selected != null && "ru_ru".equalsIgnoreCase((String) code.invoke(selected));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return false;
            }
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
        if (!QUEST.equals(name) && !NOTE.equals(name) && !ITEM.equals(name)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            Set<String> localized = new HashSet<>();
            int[] returns = {0};
            boolean[] inheritedDisplayName = {true};
            reader.accept(new ClassVisitor(Opcodes.ASM5, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                        String[] exceptions) {
                    MethodVisitor target = super.visitMethod(access, method, descriptor, signature, exceptions);
                    if (QUEST.equals(name) && "translate".equals(method) && QUEST_DESCRIPTOR.equals(descriptor)
                            && (access & Opcodes.ACC_STATIC) != 0) {
                        return new MethodVisitor(Opcodes.ASM5, target) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitLdcInsn(Type.getObjectType(name));
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "translateQuest",
                                            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/String;", false);
                                    returns[0]++;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (NOTE.equals(name) && ("func_73863_a".equals(method) && "(IIF)V".equals(descriptor)
                            || "setInConfig".equals(method) && "(II)I".equals(descriptor))) {
                        return new MethodVisitor(Opcodes.ASM5, target) {
                            @Override
                            public void visitLdcInsn(Object constant) {
                                super.visitLdcInsn(constant);
                                if (constant instanceof String && probe.containsKey(constant)) {
                                    translateText(this.mv, name);
                                    localized.add((String) constant);
                                }
                            }
                        };
                    }
                    if (ITEM.equals(name) && DISPLAY_DESCRIPTOR.equals(descriptor)
                            && (DISPLAY_NAME.equals(method) || "getItemStackDisplayName".equals(method))) {
                        inheritedDisplayName[0] = false;
                    }
                    return target;
                }
            }, 0);
            if (ITEM.equals(name) && inheritedDisplayName[0]
                    && "net/minecraft/item/Item".equals(reader.getSuperName())) {
                MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, DISPLAY_NAME, DISPLAY_DESCRIPTOR, null, null);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, reader.getSuperName(), DISPLAY_NAME, DISPLAY_DESCRIPTOR, false);
                translateText(method, name);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            } else if (returns[0] == 0 && localized.isEmpty()) {
                System.err.println("[Nuclear Tech Reborn] Book hooks did not match " + name);
                return null;
            }
            return writer.toByteArray();
        } catch (RuntimeException | LinkageError failure) {
            System.err.println("[Nuclear Tech Reborn] Book hooks skipped for " + name + ": " + failure);
            return null;
        }
    }

    private static void translateText(MethodVisitor method, String owner) {
        method.visitLdcInsn(Type.getObjectType(owner));
        method.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "translateProbe", TRANSLATE_PROBE, false);
    }
}
