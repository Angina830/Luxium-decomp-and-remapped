import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ModRemapper {
    private static final Map<String, String> methodMap = new HashMap<>();
    private static final Map<String, String> fieldMap = new HashMap<>();
    private static final Pattern SRG_METHOD_PAT = Pattern.compile("m_[0-9]+_");
    private static final Pattern SRG_FIELD_PAT = Pattern.compile("f_[0-9]+_");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: ModRemapper <input_jar> <output_jar>");
            return;
        }
        File inJar = new File(args[0]);
        File outJar = new File(args[1]);

        System.out.println("Loading method mappings...");
        loadMap(new File("tools/mappings_1_20_1/srg_methods.txt"), methodMap);
        System.out.println("Loading field mappings...");
        loadMap(new File("tools/mappings_1_20_1/srg_fields.txt"), fieldMap);
        System.out.println("Loaded " + methodMap.size() + " methods, " + fieldMap.size() + " fields.");

        Remapper remapper = new Remapper() {
            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                String m = methodMap.get(name);
                if (m != null) return m;
                m = fieldMap.get(name);
                return m != null ? m : name;
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String m = fieldMap.get(name);
                if (m != null) return m;
                m = methodMap.get(name);
                return m != null ? m : name;
            }

            @Override
            public String mapInvokeDynamicMethodName(String name, String descriptor) {
                String m = methodMap.get(name);
                if (m != null) return m;
                m = fieldMap.get(name);
                return m != null ? m : name;
            }

            @Override
            public Object mapValue(Object value) {
                if (value instanceof String s) {
                    return remapString(s);
                }
                return super.mapValue(value);
            }
        };

        System.out.println("Remapping jar: " + inJar.getAbsolutePath());
        int classCount = 0;
        int resCount = 0;

        try (ZipFile zipIn = new ZipFile(inJar);
             ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outJar)))) {

            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();

                if (name.endsWith(".class")) {
                    classCount++;
                    byte[] classBytes;
                    try (InputStream is = zipIn.getInputStream(entry)) {
                        classBytes = is.readAllBytes();
                    }

                    ClassReader cr = new ClassReader(classBytes);
                    ClassWriter cw = new ClassWriter(0); // preserve maxs/frames
                    ClassVisitor cv = new ClassRemapper(cw, remapper);
                    cr.accept(cv, ClassReader.EXPAND_FRAMES);

                    ZipEntry outEntry = new ZipEntry(name);
                    zos.putNextEntry(outEntry);
                    zos.write(cw.toByteArray());
                    zos.closeEntry();
                } else if (name.equals("luxium.mixins.refmap.json")) {
                    // Remap refmap json text
                    resCount++;
                    String text;
                    try (InputStream is = zipIn.getInputStream(entry)) {
                        text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    String remappedText = remapString(text);
                    ZipEntry outEntry = new ZipEntry(name);
                    zos.putNextEntry(outEntry);
                    zos.write(remappedText.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                } else {
                    resCount++;
                    ZipEntry outEntry = new ZipEntry(name);
                    zos.putNextEntry(outEntry);
                    try (InputStream is = zipIn.getInputStream(entry)) {
                        is.transferTo(zos);
                    }
                    zos.closeEntry();
                }
            }
        }

        System.out.println("Remapping finished: " + classCount + " classes, " + resCount + " resources written to " + outJar.getName());
    }

    private static String remapString(String s) {
        if (s == null || (!s.contains("m_") && !s.contains("f_"))) {
            return s;
        }
        // Replace m_XXXXX_
        Matcher m = SRG_METHOD_PAT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String srg = m.group();
            String mapped = methodMap.get(srg);
            m.appendReplacement(sb, mapped != null ? Matcher.quoteReplacement(mapped) : srg);
        }
        m.appendTail(sb);
        String step1 = sb.toString();

        // Replace f_XXXXX_
        Matcher f = SRG_FIELD_PAT.matcher(step1);
        StringBuilder sb2 = new StringBuilder();
        while (f.find()) {
            String srg = f.group();
            String mapped = fieldMap.get(srg);
            f.appendReplacement(sb2, mapped != null ? Matcher.quoteReplacement(mapped) : srg);
        }
        f.appendTail(sb2);
        return sb2.toString();
    }

    private static void loadMap(File file, Map<String, String> map) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }
    }
}
