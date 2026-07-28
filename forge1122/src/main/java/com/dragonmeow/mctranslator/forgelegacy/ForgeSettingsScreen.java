package com.dragonmeow.mctranslator.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import java.io.IOException;
final class ForgeSettingsScreen extends GuiScreen{
    private final GuiScreen parent;
    ForgeSettingsScreen(GuiScreen p){
        parent=p;
    }
    @Override public void initGui(){
        LegacyConfig c=MinecraftTranslatorForge.config();
        int x=width/2-155;
        addButton(new GuiButton(1,x,30,310,20,c.followGameLanguage?"Language: Game ("+MinecraftTranslatorForge.currentTarget()+")":"Language: "+c.targetLang));
        addButton(new GuiButton(2,x,54,310,20,c.enabled?"Translator: ON":"Translator: OFF"));
        addButton(new GuiButton(3,x,78,310,20,c.showOriginal?"Original + Translation":"Translation Only"));
        addButton(new GuiButton(4,x,102,152,20,"Engine: "+(c.aiEnabled?"AI":"Machine")));
        addButton(new GuiButton(5,x+158,102,152,20,"AI fallback: "+(c.disableGoogleFallbackForAi?"OFF":"ON")));
        addButton(new GuiButton(6,x,126,310,20,I18n.format("screen.mctranslator.ai.title")));
        addButton(new GuiButton(7,x,150,152,20,"Cooldown: "+(c.requestCooldownMs<=0?"OFF":c.requestCooldownMs+" ms")));
        addButton(new GuiButton(8,x+158,150,152,20,"Batch: "+(c.batchWindowMs<=0?"OFF":c.batchWindowMs/1000F+" s")));
        addButton(new GuiButton(9,x,174,310,20,"Machine provider: "+LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider)));
        addButton(new GuiButton(10,x,198,310,20,"Debug + token HUD: "+(c.debugTranslationOverlay?"ON":"OFF")));
        addButton(new GuiButton(0,width/2-100,height-22,200,20,I18n.format("gui.done")));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        LegacyConfig c=MinecraftTranslatorForge.config();
        if(b.id==0){
            MinecraftTranslatorForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(b.id==1){
            if(c.followGameLanguage){
                c.followGameLanguage=false;
                c.targetLang="zh-TW";
            } else if("zh-TW".equals(c.targetLang))c.targetLang="en";
            else c.followGameLanguage=true;
        }
        if(b.id==2)c.enabled=!c.enabled;
        if(b.id==3)c.showOriginal=!c.showOriginal;
        if(b.id==4)c.aiEnabled=!c.aiEnabled;
        if(b.id==5)c.disableGoogleFallbackForAi=!c.disableGoogleFallbackForAi;
        if(b.id==6){
            mc.displayGuiScreen(new ForgeAiConfigScreen(this));
            return;
        }
        if(b.id==7)c.requestCooldownMs=next(c.requestCooldownMs,new int[]{
            0,1000,2000,4000,6000,8000,10000
        }
        );
        if(b.id==8)c.batchWindowMs=next(c.batchWindowMs,new int[]{
            0,1000,2000,3000,5000,8000,10000
        }
        );
        if(b.id==9){
            String p=LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider);
            c.machineTranslationProvider="google".equals(p)?"youdao":"youdao".equals(p)?"deepl":"deepl".equals(p)?"microsoft":"google";
        }
        if(b.id==10){
            c.debugTranslationOverlay=!c.debugTranslationOverlay;
            if(!c.debugTranslationOverlay)MinecraftTranslatorForge.TRANSLATOR.clearDebug();
        }
        buttonList.clear();
        initGui();
    }
    private static int next(int c,int[] a){
        for(int v:a)if(v>c)return v;
        return 0;
    }
    @Override public void drawScreen(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"Minecraft Translator",width/2,16,0xFFFFFF);
        super.drawScreen(x,y,d);
    }
    @Override public void onGuiClosed(){
        MinecraftTranslatorForge.save();
    }
}
