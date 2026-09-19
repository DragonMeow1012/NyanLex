package com.dragonmeow.mctranslator.forgelegacy;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Narrow pre-layout hooks; no field access or changes to Minecraft control flow. */
public final class ScreenTextTransformer implements IClassTransformer {
    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (!"net.minecraft.client.gui.FontRenderer".equals(transformedName) || bytes == null) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) {
            boolean draw = ("renderString".equals(method.name) || "func_180455_b".equals(method.name))
                    && "(Ljava/lang/String;FFIZ)I".equals(method.desc);
            boolean wrap = ("listFormattedStringToWidth".equals(method.name) || "func_78271_c".equals(method.name))
                    && "(Ljava/lang/String;I)Ljava/util/List;".equals(method.desc);
            if (!draw && !wrap) continue;
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD,1));
            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "com/dragonmeow/mctranslator/forgelegacy/MinecraftTranslatorForge",
                    "translateScreenString", "(Ljava/lang/String;)Ljava/lang/String;", false));
            hook.add(new VarInsnNode(Opcodes.ASTORE,1));
            method.instructions.insert(hook);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer); return writer.toByteArray();
    }
}
