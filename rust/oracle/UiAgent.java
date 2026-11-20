package oracle;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;

/**
 * Lets the app build its window without a screen (for UiOracle): the app's questions about the screen (its centre,
 * size, the room for windows) get a 1920x1080 one; AWT's own labels (which need a screen) are Swing's. Loaded with -javaagent; the Java sources stay untouched.
 */
public final class UiAgent {
    public static java.awt.Point center(java.awt.GraphicsEnvironment g) {
        return new java.awt.Point(960, 540);
    }

    public static java.awt.Rectangle bounds(java.awt.GraphicsEnvironment g) {
        return new java.awt.Rectangle(0, 0, 1920, 1080);
    }

    public static java.awt.Dimension screen(java.awt.Toolkit t) {
        return new java.awt.Dimension(1920, 1080);
    }

    private static final ClassDesc SELF = ClassDesc.of("oracle.UiAgent");
    /** owner.name descriptor -> our static method, taking the receiver first. */
    private static final Map<String, String[]> SWAPS = Map.of(
            "java/awt/GraphicsEnvironment.getCenterPoint()Ljava/awt/Point;",
            new String[]{"center", "(Ljava/awt/GraphicsEnvironment;)Ljava/awt/Point;"},
            "java/awt/GraphicsEnvironment.getMaximumWindowBounds()Ljava/awt/Rectangle;",
            new String[]{"bounds", "(Ljava/awt/GraphicsEnvironment;)Ljava/awt/Rectangle;"},
            "java/awt/Toolkit.getScreenSize()Ljava/awt/Dimension;",
            new String[]{"screen", "(Ljava/awt/Toolkit;)Ljava/awt/Dimension;"});

    public static void premain(String args, Instrumentation inst) {
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> c, ProtectionDomain pd, byte[] bytes) {
                if (name == null || !(name.startsWith("lootgobbler/") || name.startsWith("tomato/") || name.startsWith("util/"))) {
                    return null;
                }
                try {
                    return rewrite(bytes, loader);
                } catch (Throwable e) {
                    System.err.println("UiAgent: " + name + " left as it is: " + e);
                    return null;
                }
            }
        });
    }

    private static byte[] rewrite(byte[] bytes, ClassLoader loader) {
        java.lang.classfile.ClassHierarchyResolver resolver = java.lang.classfile.ClassHierarchyResolver
                .ofResourceParsing(loader == null ? ClassLoader.getSystemClassLoader() : loader)
                .orElse(java.lang.classfile.ClassHierarchyResolver.ofResourceParsing(ClassLoader.getPlatformClassLoader()));
        ClassFile cf = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
        ClassDesc awtLabel = ClassDesc.of("java.awt.Label"), swingLabel = ClassDesc.of("javax.swing.JLabel");
        CodeTransform swap = (b, e) -> {
            if (e instanceof java.lang.classfile.instruction.NewObjectInstruction n && n.className().asSymbol().equals(awtLabel)) {
                b.new_(swingLabel);
                return;
            }
            if (e instanceof InvokeInstruction i && i.opcode() == Opcode.INVOKESPECIAL && i.owner().asSymbol().equals(awtLabel)
                    && i.name().stringValue().equals("<init>")) {
                b.invokespecial(swingLabel, "<init>", MethodTypeDesc.ofDescriptor(i.typeSymbol().descriptorString()));
                return;
            }
            if (e instanceof InvokeInstruction i && i.opcode() == Opcode.INVOKEVIRTUAL) {
                String[] to = SWAPS.get(i.owner().asInternalName() + "." + i.name().stringValue() + i.typeSymbol().descriptorString());
                if (to != null) {
                    b.invokestatic(SELF, to[0], MethodTypeDesc.ofDescriptor(to[1]));
                    return;
                }
            }
            b.with((CodeElement) e);
        };
        return cf.transformClass(cf.parse(bytes), ClassTransform.transformingMethodBodies(swap));
    }
}
