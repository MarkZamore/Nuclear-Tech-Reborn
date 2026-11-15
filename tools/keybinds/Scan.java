import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

public class Scan {
    static String format(AbstractInsnNode n) {
        if (n instanceof LdcInsnNode) return String.valueOf(((LdcInsnNode)n).cst);
        if (n instanceof IntInsnNode) return String.valueOf(((IntInsnNode)n).operand);
        if (n.getOpcode() >= 2 && n.getOpcode() <= 8) return String.valueOf(n.getOpcode()-3);
        if (n instanceof FieldInsnNode) { FieldInsnNode f=(FieldInsnNode)n; return f.owner+"."+f.name; }
        if (n instanceof MethodInsnNode) { MethodInsnNode m=(MethodInsnNode)n; return m.owner+"."+m.name+m.desc; }
        if (n instanceof TypeInsnNode) return ((TypeInsnNode)n).desc;
        return null;
    }
    public static void main(String[] args) throws Exception {
        try (DirectoryStream<Path> jars=Files.newDirectoryStream(Paths.get(args[0]), "*.jar")) {
            for (Path path:jars) try (JarFile jar=new JarFile(path.toFile())) {
                List<ClassNode> classes=new ArrayList<>();
                Set<String> owners=new HashSet<>(Arrays.asList("net/minecraft/client/settings/KeyBinding"));
                Enumeration<JarEntry> entries=jar.entries();
                while(entries.hasMoreElements()) {
                    JarEntry e=entries.nextElement();
                    if(!e.getName().endsWith(".class")) continue;
                    try(InputStream in=jar.getInputStream(e)) {
                        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                        byte[] buf=new byte[8192]; int n;
                        while((n=in.read(buf))!=-1) bytes.write(buf,0,n);
                        byte[] data=bytes.toByteArray();
                        String ascii=new String(data, "ISO-8859-1");
                        if(!ascii.contains("KeyBinding") && !ascii.contains("registerKeyBinding") && !ascii.contains("Keyboard") && !ascii.contains("KeyInputEvent")) continue;
                        if ((data[7]&255)>52) continue;
                        ClassNode c=new ClassNode();
                        new ClassReader(data).accept(c,ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG);
                        if(owners.contains(c.superName)) owners.add(c.name);
                        classes.add(c);
                    }
                }
                for(ClassNode c:classes) for(MethodNode m:c.methods) {
                    Deque<String> last=new ArrayDeque<>();
                    for(AbstractInsnNode n:m.instructions.toArray()) {
                        if(n instanceof MethodInsnNode) {
                            MethodInsnNode i=(MethodInsnNode)n;
                            if ((owners.contains(i.owner) && i.name.equals("<init>")) || i.name.equals("registerKeyBinding")
                                || (i.owner.equals("org/lwjgl/input/Keyboard") && i.name.equals("isKeyDown"))) {
                                System.out.println(path.getFileName()+"\t"+c.name+"."+m.name+"\t"+i.owner+"."+i.name+i.desc+"\t"+String.join(" | ",last));
                            }
                        }
                        String f=format(n);
                        if(f!=null) { if(last.size()==12) last.removeFirst(); last.addLast(f.replace("\t"," ").replace("\n"," ")); }
                    }
                }
            }
        }
    }
}
