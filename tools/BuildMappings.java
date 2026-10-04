import net.minecraftforge.srgutils.IMappingFile;
import net.minecraftforge.srgutils.IMappingFile.IClass;
import net.minecraftforge.srgutils.IMappingFile.IField;
import net.minecraftforge.srgutils.IMappingFile.IMethod;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BuildMappings {
    public static void main(String[] args) throws Exception {
        System.out.println("Loading joined.tsrg...");
        IMappingFile srg = IMappingFile.load(new File("tools/mappings_1_20_1/joined.tsrg"));
        System.out.println("Loading client.txt...");
        IMappingFile pgClient = IMappingFile.load(new File("tools/mappings_1_20_1/client.txt"));
        System.out.println("Loading server.txt...");
        IMappingFile pgServer = IMappingFile.load(new File("tools/mappings_1_20_1/server.txt"));

        Map<String, String> fields = new TreeMap<>();
        Map<String, String> methods = new TreeMap<>();
        
        // Map from client
        process(pgClient, srg, fields, methods);
        // Map from server
        process(pgServer, srg, fields, methods);

        System.out.println("Mapped fields: " + fields.size());
        System.out.println("Mapped methods: " + methods.size());

        // Write simple csv / properties
        File outDir = new File("tools/mappings_1_20_1");
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(outDir, "srg_methods.txt")), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> e : methods.entrySet()) {
                pw.println(e.getKey() + "=" + e.getValue());
            }
        }
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(new File(outDir, "srg_fields.txt")), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                pw.println(e.getKey() + "=" + e.getValue());
            }
        }

        // Also build TSRG or TSRG2 or CSRG
        // In TSRG2 / CSRG format for SpecialSource or Tiny-Remapper:
        // For CSRG:
        // field_name new_name
        // method_name signature new_name
        // But with SRG names, in CSRG:
        // In simple CSRG (or TSRG):
        // Each class:
        // net/minecraft/client/renderer/ShaderInstance
        // \tm_85850_ apply
        // \tf_172767_ sampler
        // Let's create a full srg_to_official.tsrg!
        buildTsrg(srg, pgClient, pgServer, new File(outDir, "srg_to_official.tsrg"));

        System.out.println("Mappings generated successfully!");
    }

    private static void process(IMappingFile pg, IMappingFile srg, Map<String, String> fields, Map<String, String> methods) {
        for (IClass cls : pg.getClasses()) {
            IClass obf = srg.getClass(cls.getMapped());
            if (obf != null) {
                for (IField fld : cls.getFields()) {
                    String srgName = obf.remapField(fld.getMapped());
                    if (srgName != null && (srgName.startsWith("f_") || srgName.startsWith("field_"))) {
                        fields.put(srgName, fld.getOriginal());
                    }
                }
                for (IMethod mtd : cls.getMethods()) {
                    String srgName = obf.remapMethod(mtd.getMapped(), mtd.getMappedDescriptor());
                    if (srgName != null && (srgName.startsWith("m_") || srgName.startsWith("func_"))) {
                        methods.put(srgName, mtd.getOriginal());
                    }
                }
            }
        }
    }

    private static void buildTsrg(IMappingFile srg, IMappingFile pgClient, IMappingFile pgServer, File outFile) throws Exception {
        // Collect class -> (srgMethod -> mojangMethod, srgField -> mojangField)
        Map<String, ClassData> classMap = new TreeMap<>();

        addMappings(classMap, pgClient, srg);
        addMappings(classMap, pgServer, srg);

        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            pw.println("tsrg2 left right");
            for (Map.Entry<String, ClassData> entry : classMap.entrySet()) {
                String clsName = entry.getKey();
                ClassData cd = entry.getValue();
                pw.println(clsName + " " + clsName);
                for (Map.Entry<String, String> fe : cd.fields.entrySet()) {
                    pw.println("\t" + fe.getKey() + " " + fe.getValue());
                }
                for (MethodData md : cd.methods) {
                    pw.println("\t" + md.srgName + " " + md.desc + " " + md.mojangName);
                }
            }
        }
    }

    private static class ClassData {
        Map<String, String> fields = new TreeMap<>();
        List<MethodData> methods = new ArrayList<>();
        Set<String> seenMethods = new HashSet<>();
    }

    private static class MethodData {
        String srgName;
        String desc;
        String mojangName;
        MethodData(String srgName, String desc, String mojangName) {
            this.srgName = srgName;
            this.desc = desc;
            this.mojangName = mojangName;
        }
    }

    private static void addMappings(Map<String, ClassData> classMap, IMappingFile pg, IMappingFile srg) {
        for (IClass cls : pg.getClasses()) {
            IClass obf = srg.getClass(cls.getMapped());
            if (obf != null) {
                // The class name in source/mod is Mojang's class name (or SRG class name which equals Mojang for MCP)
                String mojangClassName = cls.getOriginal();
                ClassData cd = classMap.computeIfAbsent(mojangClassName, k -> new ClassData());

                for (IField fld : cls.getFields()) {
                    String srgName = obf.remapField(fld.getMapped());
                    if (srgName != null && (srgName.startsWith("f_") || srgName.startsWith("field_"))) {
                        cd.fields.put(srgName, fld.getOriginal());
                    }
                }

                for (IMethod mtd : cls.getMethods()) {
                    String srgName = obf.remapMethod(mtd.getMapped(), mtd.getMappedDescriptor());
                    if (srgName != null && (srgName.startsWith("m_") || srgName.startsWith("func_"))) {
                        // Descriptor in SRG format:
                        // Since class names in 1.20.1 are the same between SRG and Mojang, descriptor is identical!
                        String desc = mtd.getDescriptor();
                        String key = srgName + ":" + desc;
                        if (cd.seenMethods.add(key)) {
                            cd.methods.add(new MethodData(srgName, desc, mtd.getOriginal()));
                        }
                    }
                }
            }
        }
    }
}
