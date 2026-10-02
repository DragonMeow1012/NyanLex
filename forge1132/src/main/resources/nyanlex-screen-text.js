function initializeCoreMod() {
    var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
    var Opcodes = Java.type('org.objectweb.asm.Opcodes');
    var InsnList = Java.type('org.objectweb.asm.tree.InsnList');
    var VarInsnNode = Java.type('org.objectweb.asm.tree.VarInsnNode');
    var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');
    function hook(method) {
        var code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            'com/dragonmeow/nyanlex/forgelegacy/NyanLexForge',
            'translateScreenString', '(Ljava/lang/String;)Ljava/lang/String;', false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        method.instructions.insert(code);
        return method;
    }
    return {
        'screen_draw': {
            'target': {'type':'METHOD', 'class':'net.minecraft.client.gui.FontRenderer',
                'methodName':ASMAPI.mapMethod('func_180455_b'), 'methodDesc':'(Ljava/lang/String;FFIZ)I'},
            'transformer':hook
        },
        'screen_wrap': {
            'target': {'type':'METHOD', 'class':'net.minecraft.client.gui.FontRenderer',
                'methodName':ASMAPI.mapMethod('func_78271_c'), 'methodDesc':'(Ljava/lang/String;I)Ljava/util/List;'},
            'transformer':hook
        }
    };
}
