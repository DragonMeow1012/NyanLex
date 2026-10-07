"""Exercise the five actual Java-8 chat adapters without launching Minecraft."""
import argparse,json,os,subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--module',choices=['fabric1144','fabric1152','fabric1165','forge1122','forge1132'],required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--classpath-report',type=Path)
args=parser.parse_args()
out=args.output.resolve(); out.mkdir(parents=True,exist_ok=True)
evidence=json.loads((args.classpath_report or root/args.module/'build/port-verification.json').read_text(encoding='utf-8'))
forge=args.module.startswith('forge')
package='com.dragonmeow.nyanlex.'+('forgelegacy' if forge else 'legacy')
values={'PACKAGE':package,'MAIN':package+('.NyanLexForge' if forge else '.LegacyTranslatorMod'),
        'COMPONENT':'net.minecraft.util.text.ITextComponent' if forge else 'net.minecraft.network.chat.Component',
        'COMPONENT_SIMPLE':'ITextComponent' if forge else 'Component',
        'CHAT':'net.minecraft.client.gui.GuiNewChat' if forge else 'net.minecraft.client.gui.components.ChatComponent',
        'CHAT_SIMPLE':'GuiNewChat' if forge else 'ChatComponent',
        'HISTORY':'net.minecraft.client.gui.ChatLine' if forge else 'net.minecraft.client.GuiMessage',
        'HISTORY_SIMPLE':'ChatLine' if forge else 'GuiMessage',
        'GUI':'net.minecraft.client.gui.GuiIngame' if forge else 'net.minecraft.client.gui.Gui',
        'GUI_SIMPLE':'GuiIngame' if forge else 'Gui',
        'GUI_FIELD':'ingameGUI' if forge else 'gui','GET_CHAT':'getChatGUI' if forge else 'getChat',
        'CONFIG_OWNER':'adapter' if forge else 'null','ADD':'printChatMessage' if forge else 'addMessage',
        'RESCALE':'refreshChat' if forge else 'rescaleChat','CONTENT':'getChatComponent' if forge else 'getMessage',
        'TEXT':'getUnformattedText' if args.module=='forge1122' else 'getString','TIME':'getUpdatedCounter' if forge else 'getAddedTime',
        'ACCESS_IMPORT':'' if forge else 'import '+package+'.LegacyChatComponentAccess;',
        'ACCESS_IMPLEMENT':'' if forge else 'implements LegacyChatComponentAccess',
        'ACCESS_METHOD':'' if forge else '@Override public List<GuiMessage> nyanlex$getAllMessages() { return history; }',
        'INSTALL_HISTORY':'set(chat,GuiNewChat.class,"chatLines",chat.history);' if forge else '',
        'GUI_ADD':'@Override public void addChatMessage(net.minecraft.util.text.ChatType type,ITextComponent text) { chat.printChatMessage(text); }' if forge else '',
        'QUEUE':'return invoke(adapter,"queueChat",client,net.minecraft.util.text.ChatType.CHAT,new net.minecraft.util.text.TextComponentString(text),text);' if forge else '''Class<?> pendingType=Class.forName(adapterType.getName()+"$PendingChat");
        Constructor<?> ctor=pendingType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Object pending=ctor.newInstance(field(adapter,"chatEpoch"),null,null,profile,new net.minecraft.network.chat.TextComponent(text),text,field(config,"showOriginal"));
        invoke(adapter,"enqueueChat",client,pending); return pending;''',
        'COMPLETE':'set(pending,pending.getClass(),"translated",text); invoke(field(adapter,"pendingChats"),"markReady",pending); invoke(adapter,"flushReadyChats",client);' if forge else 'invoke(adapter,"completeChat",client,pending,text);',
        'EXPIRE':'invoke(adapter,"expireTimedOutChats",client,System.nanoTime());' if forge else 'invoke(adapter,"flushPendingChats",client);',
        'FLUSH':'invoke(adapter,"flushPendingChatOriginals",client);' if forge else 'invoke(adapter,"flushAllOriginals",client);'}
source=(root/'verification/chat/LegacyImmediateChatRegression.java.in').read_text(encoding='utf-8')
for key,value in values.items():source=source.replace('@'+key+'@',value)
if args.module=='forge1132':
    # Its real constructor registers key bindings with the running Forge client.
    # Initialize only the existing chat-owned state for this headless simulation.
    source=source.replace('adapter=adapterType.getDeclaredConstructor().newInstance();', '''adapter=MEMORY.allocateInstance(adapterType);
        Class<?> queueType=Class.forName("com.dragonmeow.nyanlex.forgelegacy.LegacyChatDeliveryQueue");
        Constructor<?> queueConstructor=queueType.getDeclaredConstructor(); queueConstructor.setAccessible(true);
        set(adapter,adapterType,"pendingChats",queueConstructor.newInstance());
        set(adapter,adapterType,"pendingChatById",new LinkedHashMap<Long,Object>());
        set(adapter,adapterType,"nextChatId",1L);''')
path=out/'LegacyImmediateChatRegression.java';path.write_text(source,encoding='utf-8')
classpath=os.pathsep.join([evidence['classesDir'],evidence['resourcesDir'],*evidence['compileClasspath']])
java=root/'.jdks/temurin8/jdk8u492-b09/bin' if forge else Path('C:/Program Files/Java/jdk-25/bin')
flags=['-source','8','-target','8'] if forge else ['--release','25']
commands=[[str(java/'javac.exe'),*flags,'-encoding','UTF-8','-proc:none','-implicit:none','-cp',classpath,'-d',str(out),str(path)],
          [str(java/'java.exe'),'-cp',str(out)+os.pathsep+classpath,'LegacyImmediateChatRegression']]
results=[]
for step,command in zip(['compile','simulate'],commands):
    proc=subprocess.run(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    (out/(step+'.log')).write_text(proc.stdout,encoding='utf-8');print(proc.stdout,flush=True)
    results.append(dict(step=step,exit_code=proc.returncode,command=command))
    if proc.returncode:sys.exit(proc.returncode)
(out/'summary.json').write_text(json.dumps(dict(status='passed',module=args.module,clientStarted=False,providerCalls=0,classSource=evidence['classesDir'],commands=results),indent=2)+'\n',encoding='utf-8')
