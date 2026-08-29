package com.dragonmeow.mctranslator.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
final class ForgeSettingsScreen extends GuiScreen implements ForgeButton.Handler{
    private final GuiScreen parent;
    ForgeSettingsScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        LegacyConfig c=MinecraftTranslatorForge.config();
        int x=width/2-155;
        addButton(new ForgeButton(1,x,30,310,20,c.followGameLanguage?"Language: Game ("+MinecraftTranslatorForge.currentTarget()+")":"Language: "+c.targetLang,this));
        addButton(new ForgeButton(2,x,54,310,20,c.enabled?"Translator: ON":"Translator: OFF",this));
        addButton(new ForgeButton(3,x,78,310,20,c.showOriginal?"Original + Translation":"Translation Only",this));
        addButton(new ForgeButton(4,x,102,152,20,"Engine: "+(c.aiEnabled?"AI":"Machine"),this));
        addButton(new ForgeButton(5,x+158,102,152,20,"AI fallback: "+(c.disableGoogleFallbackForAi?"OFF":"ON"),this));
        addButton(new ForgeButton(6,x,126,310,20,I18n.format("screen.mctranslator.ai.title"),this));
        addButton(new ForgeButton(7,x,150,152,20,"Cooldown: "+(c.requestCooldownMs<=0?"OFF":c.requestCooldownMs+" ms"),this));
        addButton(new ForgeButton(8,x+158,150,152,20,"Batch: "+(c.batchWindowMs<=0?"OFF":c.batchWindowMs/1000F+" s"),this));
        addButton(new ForgeButton(9,x,174,152,20,"Machine: "+LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider),this));
        addButton(new ForgeButton(10,x+158,174,152,20,"Debug HUD: "+(c.debugTranslationOverlay?"ON":"OFF"),this));
        addButton(new ForgeButton(11,x,198,310,20,chatDeliveryLabel(c),this));
        addButton(new ForgeButton(0,width/2-100,height-22,200,20,I18n.format("gui.done") ,this));
    }
    @Override public void onForgeButton(GuiButton b){
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
        if(b.id==11)c.deliverChatTranslationsInOrder=!c.deliverChatTranslationsInOrder;
        buttons.clear();
        children.clear();
        initGui();
    }
    private static int next(int c,int[] a){
        for(int v:a)if(v>c)return v;
        return 0;
    }
    private static String chatDeliveryLabel(LegacyConfig c){
        String mode=I18n.format(c.deliverChatTranslationsInOrder
                ?"config.mctranslator.chat_delivery.ordered"
                :"config.mctranslator.chat_delivery.ready_first");
        return I18n.format("config.mctranslator.chat_delivery",mode);
    }
    @Override public void render(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"Minecraft Translator",width/2,16,0xFFFFFF);
        super.render(x,y,d);
    }
    @Override public void onGuiClosed(){
        MinecraftTranslatorForge.save();
    }
}
