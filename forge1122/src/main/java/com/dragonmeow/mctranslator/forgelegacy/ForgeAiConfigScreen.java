package com.dragonmeow.mctranslator.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
final class ForgeAiConfigScreen extends GuiScreen{
    private static final String OPENAI="https://api.openai.com/v1",GEMINI="https://generativelanguage.googleapis.com/v1beta/openai";
    private final GuiScreen parent;
    private GuiTextField endpoint,model,keys;
    private String status="";
    private boolean refreshed;
    ForgeAiConfigScreen(GuiScreen p){
        parent=p;
    }
    @Override public void initGui(){
        rebuild();
        if(MinecraftTranslatorForge.config().aiUseCodex&&!refreshed){
            refreshed=true;
            refresh(false);
        }
    }
    private void rebuild(){
        buttonList.clear();
        endpoint=model=keys=null;
        LegacyConfig c=MinecraftTranslatorForge.config();
        int x=width/2-160;
        addButton(new GuiButton(1,x,24,62,20,label("Gemini",!c.aiUseCodex&&same(c.aiBaseUrl,GEMINI))));
        addButton(new GuiButton(2,x+65,24,62,20,label("DeepSeek",!c.aiUseCodex&&same(c.aiBaseUrl,"https://api.deepseek.com"))));
        addButton(new GuiButton(3,x+130,24,62,20,label("Custom",!c.aiUseCodex&&!known(c.aiBaseUrl))));
        addButton(new GuiButton(4,x+195,24,125,20,label("OpenAI",c.aiUseCodex||same(c.aiBaseUrl,OPENAI))));
        if(c.aiUseCodex)codex(x);
        else api(x,c);
        addButton(new GuiButton(0,width/2-100,height-26,200,20,I18n.format("gui.done")));
    }
    private void api(int x,LegacyConfig c){
        boolean open=same(c.aiBaseUrl,OPENAI);
        if(open){
            addButton(new GuiButton(5,x,48,157,20,label("API key",true)));
            addButton(new GuiButton(6,x+163,48,157,20,label("ChatGPT login",false)));
        }
        int y=open?74:50;
        endpoint=new GuiTextField(20,fontRenderer,x,y,320,20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(s(c.aiBaseUrl));
        model=new GuiTextField(21,fontRenderer,x,y+34,320,20);
        model.setMaxStringLength(128);
        model.setText(s(c.aiModel));
        keys=new GuiTextField(22,fontRenderer,x,y+68,320,20);
        keys.setMaxStringLength(4096);
        keys.setText(keysFor(c,c.aiBaseUrl));
        addButton(new GuiButton(7,x,y+94,320,20,I18n.format("screen.mctranslator.ai.test")));
    }
    private void codex(int x){
        LegacyCodexClient cl=MinecraftTranslatorForge.codexClient();
        boolean signed=cl!=null&&cl.isSignedInCached();
        addButton(new GuiButton(5,x,48,157,20,label("API key",false)));
        addButton(new GuiButton(6,x+163,48,157,20,label("ChatGPT login",true)));
        addButton(new GuiButton(8,x,78,320,20,signed?I18n.format("screen.mctranslator.ai.codex.logout"):I18n.format("screen.mctranslator.ai.codex.login")));
        addButton(new GuiButton(9,x,104,252,20,"Model: "+MinecraftTranslatorForge.config().codexModel));
        addButton(new GuiButton(10,x+258,104,62,20,I18n.format("screen.mctranslator.ai.codex.refresh")));
        addButton(new GuiButton(11,x,130,320,20,"Reasoning: "+MinecraftTranslatorForge.config().codexReasoningEffort));
        addButton(new GuiButton(12,x,156,320,20,I18n.format("screen.mctranslator.ai.test")));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        LegacyConfig c=MinecraftTranslatorForge.config();
        if(b.id==0){
            saveFields();
            MinecraftTranslatorForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(b.id==1)switchApi(GEMINI,"gemini-3.1-flash-lite");
        if(b.id==2)switchApi("https://api.deepseek.com","deepseek-chat");
        if(b.id==3)switchApi(c.aiBaseUrl,c.aiModel);
        if(b.id==4)switchApi(OPENAI,"gpt-5.4-mini");
        if(b.id==5){
            saveFields();
            c.aiUseCodex=false;
            rebuild();
        }
        if(b.id==6){
            saveFields();
            c.aiBaseUrl=OPENAI;
            c.aiUseCodex=true;
            rebuild();
            refresh(false);
        }
        if(b.id==7){
            saveFields();
            test();
        }
        if(b.id==8){
            if(MinecraftTranslatorForge.codexClient().isSignedInCached())logout();
            else login();
        }
        if(b.id==9)mc.displayGuiScreen(new ForgeCodexModelScreen(this));
        if(b.id==10)refresh(true);
        if(b.id==11)mc.displayGuiScreen(new ForgeCodexEffortScreen(this));
        if(b.id==12)test();
    }
    private void switchApi(String url,String def){
        saveFields();
        LegacyConfig c=MinecraftTranslatorForge.config();
        boolean changed=!same(c.aiBaseUrl,url);
        c.aiUseCodex=false;
        c.aiBaseUrl=url;
        if(changed)c.aiModel=def;
        c.aiApiKeys=parse(keysFor(c,url));
        rebuild();
    }
    private void saveFields(){
        LegacyConfig c=MinecraftTranslatorForge.config();
        if(c.aiUseCodex||endpoint==null)return;
        c.aiBaseUrl=endpoint.getText().trim();
        c.aiModel=model.getText().trim();
        c.aiApiKeys=parse(keys.getText());
        c.aiKeysByEndpoint.put(key(c.aiBaseUrl),keys.getText());
    }
    private void refresh(final boolean user){
        final LegacyCodexClient cl=MinecraftTranslatorForge.codexClient();
        if(cl==null)return;
        setStatus("Loading Codex...");
        async("refresh",()->{
            try{
                cl.readAccount(user);if(cl.isSignedInCached()){
                    List<LegacyCodexClient.ModelOption> o=cl.listModels();normalize(o);setStatus("Loaded "+o.size()+" models");
                } else setStatus("ChatGPT signed out");
            } catch(Exception e){
                setStatus("Failed: "+err(e));
            }
        }
        );
    }
    private void login(){
        final LegacyCodexClient cl=MinecraftTranslatorForge.codexClient();
        setStatus("Opening ChatGPT login...");
        async("login",()->{
            try{
                if(!cl.isInstalled()){
                    setStatus("Codex CLI not installed");open("https://openai.com/codex/get-started/");return;
                }
                LegacyCodexClient.LoginStart l=cl.startLogin();open(l.authUrl());if(!cl.awaitLogin(l.loginId(),600000))throw new IOException("login timed out");normalize(cl.listModels());setStatus("ChatGPT sign-in complete");
            } catch(Exception e){
                setStatus("Failed: "+err(e));
            }
        }
        );
    }
    private void logout(){
        final LegacyCodexClient cl=MinecraftTranslatorForge.codexClient();
        setStatus("Signing out...");
        async("logout",()->{
            try{
                cl.logout();setStatus("Signed out");
            } catch(Exception e){
                setStatus("Failed: "+err(e));
            }
        }
        );
    }
    private void test(){
        setStatus("Testing...");
        MinecraftTranslatorForge.testAi(this::setStatus);
    }
    private void normalize(List<LegacyCodexClient.ModelOption> opts){
        LegacyConfig c=MinecraftTranslatorForge.config();
        LegacyCodexClient.ModelOption p=null;
        for(LegacyCodexClient.ModelOption o:opts)if(o.model().equals(c.codexModel))p=o;
        if(p==null)for(LegacyCodexClient.ModelOption o:opts)if(o.isDefault()){
            p=o;
            break;
        }
        if(p==null&&!opts.isEmpty())p=opts.get(0);
        if(p!=null){
            c.codexModel=p.model();
            normalizeEffort(c,p);
        }
        MinecraftTranslatorForge.save();
    }
    static void normalizeEffort(LegacyConfig c,LegacyCodexClient.ModelOption o){
        List<String> e=o.reasoningEfforts();
        if(e.isEmpty()){
            c.codexReasoningEffort="";
            return;
        }
        if(!e.contains(c.codexReasoningEffort))c.codexReasoningEffort=e.contains(o.defaultReasoningEffort())?o.defaultReasoningEffort():e.get(0);
    }
    private void setStatus(final String v){
        if(mc==null){
            status=v;
            return;
        }
        mc.addScheduledTask(()->{
            status=v;rebuild();
        }
        );
    }
    private static void async(String n,Runnable r){
        Thread t=new Thread(r,"mctranslator-codex-"+n);
        t.setDaemon(true);
        t.start();
    }
    private static void open(String u){
        try{
            if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(new URI(u));
        } catch(Exception ignored){
        }
    }
    @Override protected void keyTyped(char ch,int code)throws IOException{
        if(code==1){
            saveFields();
            MinecraftTranslatorForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(endpoint!=null&&endpoint.textboxKeyTyped(ch,code))return;
        if(model!=null&&model.textboxKeyTyped(ch,code))return;
        if(keys!=null&&keys.textboxKeyTyped(ch,code))return;
        super.keyTyped(ch,code);
    }
    @Override protected void mouseClicked(int x,int y,int b)throws IOException{
        super.mouseClicked(x,y,b);
        if(endpoint!=null)endpoint.mouseClicked(x,y,b);
        if(model!=null)model.mouseClicked(x,y,b);
        if(keys!=null)keys.mouseClicked(x,y,b);
    }
    @Override public void updateScreen(){
        if(endpoint!=null)endpoint.updateCursorCounter();
        if(model!=null)model.updateCursorCounter();
        if(keys!=null)keys.updateCursorCounter();
    }
    @Override public void drawScreen(int mx,int my,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.mctranslator.ai.title"),width/2,8,0xFFFFFF);
        if(endpoint!=null){
            fontRenderer.drawString("Endpoint",endpoint.x,endpoint.y-10,0xA0A0A0);
            endpoint.drawTextBox();
            fontRenderer.drawString("Model",model.x,model.y-10,0xA0A0A0);
            model.drawTextBox();
            fontRenderer.drawString("API keys",keys.x,keys.y-10,0xA0A0A0);
            keys.drawTextBox();
        }
        if(MinecraftTranslatorForge.config().aiUseCodex){
            LegacyCodexClient.AccountSnapshot a=MinecraftTranslatorForge.codexClient().cachedAccount();
            drawCenteredString(fontRenderer,a.signedIn()?"Signed in"+(a.email()==null?"":": "+a.email()):"Signed out",width/2,182,0xA0A0A0);
        }
        if(!status.isEmpty())drawCenteredString(fontRenderer,status,width/2,196,0xFFD080);
        super.drawScreen(mx,my,d);
    }
    @Override public void onGuiClosed(){
        saveFields();
        MinecraftTranslatorForge.save();
    }
    private static String label(String s,boolean on){
        return on?"["+s+"]":s;
    }
    private static String s(String v){
        return v==null?"":v;
    }
    private static boolean same(String a,String b){
        return key(a).equals(key(b));
    }
    private static String key(String v){
        String x=s(v).trim().toLowerCase(Locale.ROOT);
        while(x.endsWith("/"))x=x.substring(0,x.length()-1);
        return x;
    }
    private static boolean known(String u){
        return same(u,OPENAI)||same(u,GEMINI)||same(u,"https://api.deepseek.com");
    }
    private static String keysFor(LegacyConfig c,String u){
        String v=c.aiKeysByEndpoint.get(key(u));
        if(v!=null)return v;
        StringBuilder b=new StringBuilder();
        for(String x:c.aiApiKeys){
            if(x==null||x.trim().isEmpty())continue;
            if(b.length()>0)b.append(", ");
            b.append(x.trim());
        }
        return b.toString();
    }
    private static List<String> parse(String v){
        List<String> r=new ArrayList<String>();
        for(String x:s(v).split("[,;\\r\\n]+"))if(!x.trim().isEmpty())r.add(x.trim());
        return r;
    }
    private static String err(Throwable e){
        return e.getMessage()==null?"unknown":e.getMessage();
    }
}
