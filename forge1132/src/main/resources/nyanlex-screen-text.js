// Forge 1.13.2 ships the coremods library 0.5.0, which only builds transformers for CLASS targets.
// A METHOD target makes it return null for that entry and ModLauncher then fails with a NullPointerException
// before any mod is loaded. Both patches therefore target the whole FontRenderer class; each transformer receives
// the ClassNode, finds its method by name and descriptor, and applies the same insertion as before.
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
    // The method is matched by descriptor plus either its SRG name (production) or the name the loader maps it to
    // (development environment). A missing method is reported as an error instead of being skipped silently.
    function patcher(srgName, desc) {
        var mappedName = String(ASMAPI.mapMethod(srgName));
        return function (classNode) {
            var methods = classNode.methods;
            for (var i = 0; i < methods.size(); i++) {
                var method = methods.get(i);
                var name = String(method.name);
                if (String(method.desc) === desc && (name === srgName || name === mappedName)) {
                    hook(method);
                    return classNode;
                }
            }
            throw new Error('NyanLex: method ' + srgName + desc + ' not found in ' + String(classNode.name));
        };
    }
    return {
        'screen_draw': {
            'target': {'type':'CLASS', 'name':'net.minecraft.client.gui.FontRenderer'},
            'transformer':patcher('func_180455_b', '(Ljava/lang/String;FFIZ)I')
        },
        'screen_wrap': {
            'target': {'type':'CLASS', 'name':'net.minecraft.client.gui.FontRenderer'},
            'transformer':patcher('func_78271_c', '(Ljava/lang/String;I)Ljava/util/List;')
        }
    };
}
