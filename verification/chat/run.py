"""Check the actual modern chat adapter against an in-memory game history.

This uses a verified named game classpath and freshly compiled project outputs.
It never initializes a game client constructor or calls a translation provider.
"""
import argparse, hashlib, json, os, subprocess, sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--module', choices=['fabric1171','fabric1182','fabric1194','fabric120','fabric12111','fabric2612', 'fabric26', '.', 'neoforge','neoforge120', 'neoforge26'], default='fabric2612')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
project = root / args.module
evidence = json.loads((project/'build/port-verification.json').read_text(encoding='utf-8'))
java = Path('C:/Program Files/Java/jdk-25/bin')
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
package='neoforge26' if args.module=='neoforge26' else 'neoforge' if args.module.startswith('neoforge') else 'fabric26' if args.module in ('fabric2612','fabric26') else 'fabric'
main = 'com.dragonmeow.nyanlex.'+package+'.'+('NyanLexNeoForge' if package=='neoforge' else 'NyanLexNeoForge26' if package=='neoforge26' else 'NyanLexFabric26' if package=='fabric26' else 'NyanLexFabric')
access='com.dragonmeow.nyanlex.'+package+('.mixin.ChatComponentAccessor' if package.endswith('26') else '.ChatComponentAccess')
new = package.endswith('26')
early=args.module in ('fabric1171','fabric1182')
hud = args.module in ('fabric26', 'neoforge26')
values = {'MAIN':main,'ACCESS':access,'ACCESS_SIMPLE':access.rsplit('.',1)[1],
          'HISTORY':'net.minecraft.client.multiplayer.chat.GuiMessage' if new else 'net.minecraft.client.GuiMessage',
          'HISTORY_TYPE':'GuiMessage<Component>' if early else 'GuiMessage',
          'CONTENT':'getMessage' if early else 'content','TIME':'getAddedTime' if early else 'addedTime','ADD':'addClientSystemMessage' if new else 'addMessage',
          'PARAM':', null' if args.module.startswith('fabric') or args.module=='.' else '',
          'GUI_BASE':'net.minecraft.client.gui.Hud' if hud else 'Gui',
          'INSTALL_GUI':'Gui gui=(Gui)MEMORY.allocateInstance(Gui.class); set(gui,Gui.class,"hud",screen); set(client,Minecraft.class,"gui",gui);' if hud else 'set(client,Minecraft.class,"gui",screen);'}
source = (root/'verification/chat/ImmediateChatRegression.java.in').read_text(encoding='utf-8')
for key,value in values.items(): source=source.replace('@'+key+'@',value)
if args.module in ('fabric1194', 'fabric120', 'neoforge120'):
    source=source.replace('Screen() { super(null); }', 'Screen() { super(null, null); }')
if early:
    source=source.replace('Component.literal(', 'new net.minecraft.network.chat.TextComponent(')
path = out/'ImmediateChatRegression.java'
path.write_text(source,encoding='utf-8')
classpath = os.pathsep.join([evidence['classesDir'],evidence['resourcesDir'],*evidence['compileClasspath']])
commands = [
 [str(java/'javac.exe'),'--release','25','-encoding','UTF-8','-proc:none','-implicit:none','-cp',classpath,'-d',str(out),str(path)],
 [str(java/'java.exe'),'-cp',str(out)+os.pathsep+classpath,'ImmediateChatRegression']]
results=[]
for step,command in zip(['compile','simulate'],commands):
    p=subprocess.run(command,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    (out/(step+'.log')).write_text(p.stdout,encoding='utf-8')
    print(p.stdout,flush=True)
    results.append({'step':step,'exit_code':p.returncode,'command':command})
    if p.returncode:
        sys.exit(p.returncode)
report={'status':'passed','module':args.module,'clientStarted':False,'providerCalls':0,'classSource':evidence['classesDir'],'commands':results}
(out/'summary.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
