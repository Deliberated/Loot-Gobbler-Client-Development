package oracle;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Lets the oracle run the app on a recording's clock: the app's own classes (lootgobbler, tomato, packets, util) get
 * every System.currentTimeMillis / nanoTime and LocalDate / LocalDateTime / Instant .now() rewritten to read
 * {@link #now}, which the replay sets to each frame's capture time. So "every 5 s" in the app means 5 s of the
 * recording, as in the Rust port, however fast the replay runs. The Java sources stay untouched.
 * <p>
 * Loaded with -javaagent (compare.sh packs it); only for comparing the Rust port.
 */
public final class ClockAgent {
    /** The recording's time, ms since 1970; 0 until the first frame. */
    public static volatile long now;

    public static long millis() {
        return now;
    }

    public static long nanos() {
        return now * 1_000_000L;
    }

    public static Instant instant() {
        return Instant.ofEpochMilli(now);
    }

    public static LocalDate localDate() {
        return LocalDate.ofInstant(instant(), ZoneId.systemDefault());
    }

    public static LocalDateTime localDateTime() {
        return LocalDateTime.ofInstant(instant(), ZoneId.systemDefault());
    }

    private static final ClassDesc SELF = ClassDesc.of("oracle.ClockAgent");

    /** owner.name()descriptor -> the replacement here. */
    private static final Map<String, String> SWAPS = Map.of(
            "java/lang/System.currentTimeMillis", "millis",
            "java/lang/System.nanoTime", "nanos",
            "java/time/Instant.now", "instant",
            "java/time/LocalDate.now", "localDate",
            "java/time/LocalDateTime.now", "localDateTime");

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> c, ProtectionDomain pd, byte[] bytes) {
                if (name == null || !(name.startsWith("lootgobbler/") || name.startsWith("tomato/")
                        || name.startsWith("packets/") || name.startsWith("util/") || name.startsWith("assets/"))) {
                    return null;
                }
                try {
                    return rewrite(bytes, loader);
                } catch (Throwable e) {
                    System.err.println("ClockAgent: " + name + " left as it is: " + e);
                    return null;
                }
            }
        });
    }

    private static byte[] rewrite(byte[] bytes, ClassLoader loader) {
        // the stack maps are worked out again: by reading class files, never loading classes (which would define
        // the class being transformed a second time)
        java.lang.classfile.ClassHierarchyResolver resolver = java.lang.classfile.ClassHierarchyResolver
                .ofResourceParsing(loader == null ? ClassLoader.getSystemClassLoader() : loader)
                .orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(ClassLoader.getPlatformClassLoader()));
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        CodeTransform swap = (b, e) -> {
            if (e instanceof InvokeInstruction i && i.method().type().stringValue().startsWith("()")) {
                String key = i.owner().asInternalName() + "." + i.name().stringValue();
                String to = SWAPS.get(key);
                if (to != null && i.isInterface() == false && i.opcode() == java.lang.classfile.Opcode.INVOKESTATIC) {
                    b.invokestatic(SELF, to, MethodTypeDesc.ofDescriptor(i.typeSymbol().descriptorString()));
                    return;
                }
            }
            b.with((CodeElement) e);
        };
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(swap));
    }
}
