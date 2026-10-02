package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
final class ForgeSettingsScreen extends GuiScreen implements ForgeButton.Handler{
    private final GuiScreen parent;
    ForgeSettingsScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-155;
        // Prominent (but non-blocking) entry point to the help screen, top-left corner.
        addButton(new ForgeButton(16,6,4,70,14,"§e"+I18n.format("config.nyanlex.help.open"),this));
        addButton(new ForgeButton(1,x,30,310,20,c.followGameLanguage?"Language: Game ("+NyanLexForge.currentTarget()+")":"Language: "+c.targetLang,this));
        addButton(new ForgeButton(2,x,50,152,20,c.enabled?"Translator: ON":"Translator: OFF",this));
        addButton(new ForgeButton(14,x+158,50,152,20,I18n.format("config.nyanlex.requests.open"),this));
        addButton(new ForgeButton(3,x,70,310,20,c.showOriginal?"Original + Translation":"Translation Only",this));
        addButton(new ForgeButton(4,x,90,152,20,"Engine: "+(c.aiEnabled?"AI":"Machine"),this));
        addButton(new ForgeButton(5,x+158,90,152,20,machineFallbackLabel(c),this));
        addButton(new ForgeButton(6,x,110,310,20,I18n.format("screen.nyanlex.ai.title"),this));
        // Request cooldown + batch window merged into one submenu (was two half-width cyclers).
        addButton(new ForgeButton(7,x,130,310,20,I18n.format("config.nyanlex.request_cooldown.open"),this));
        addButton(new ForgeButton(9,x,150,152,20,"Machine: "+LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider),this));
        addButton(new ForgeButton(10,x+158,150,152,20,"Debug HUD: "+(c.debugTranslationOverlay?"ON":"OFF"),this));
        addButton(new ForgeButton(11,x,170,310,20,chatDeliveryLabel(c),this));
        addButton(new ForgeButton(15,x,height-46,310,20,requestsToggleLabel(c),this));
        addButton(new ForgeButton(12,x,height-22,100,20,I18n.format("config.nyanlex.translations.export"),this));
        addButton(new ForgeButton(13,x+105,height-22,100,20,I18n.format("config.nyanlex.translations.import"),this));
        addButton(new ForgeButton(0,x+210,height-22,100,20,I18n.format("gui.done") ,this));
    }
    @Override public void onForgeButton(GuiButton b){
        LegacyConfig c=NyanLexForge.config();
        if(b.id==0){
            NyanLexForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(b.id==12 || b.id==13){ NyanLexForge.translationFile(b.id==13); return; }
        if(b.id==16){
            mc.displayGuiScreen(new ForgeHelpScreen(this));
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
        if(b.id==14){
            mc.displayGuiScreen(new ForgeRequestsScreen(this));
            return;
        }
        if(b.id==7){
            mc.displayGuiScreen(new ForgeCooldownScreen(this));
            return;
        }
        if(b.id==9){
            String p=LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider);
            c.machineTranslationProvider="google".equals(p)?"youdao":"youdao".equals(p)?"deepl":"deepl".equals(p)?"microsoft":"google";
        }
        if(b.id==10){
            c.debugTranslationOverlay=!c.debugTranslationOverlay;
            if(!c.debugTranslationOverlay)NyanLexForge.TRANSLATOR.clearDebug();
        }
        if(b.id==11)c.deliverChatTranslationsInOrder=!c.deliverChatTranslationsInOrder;
        if(b.id==15)c.translationRequestsEnabled=!c.translationRequestsEnabled;
        buttons.clear();
        children.clear();
        initGui();
    }
    static int next(int c,int[] a){
        for(int v:a)if(v>c)return v;
        return 0;
    }
    /** Same field as before; label text now explains the effect instead of naming "GT". */
    private static String machineFallbackLabel(LegacyConfig c){
        return I18n.format("config.nyanlex.ai.machine_fallback",c.disableGoogleFallbackForAi?"OFF":"ON");
    }
    private static String chatDeliveryLabel(LegacyConfig c){
        String mode=I18n.format(c.deliverChatTranslationsInOrder
                ?"config.nyanlex.chat_delivery.ordered"
                :"config.nyanlex.chat_delivery.ready_first");
        return I18n.format("config.nyanlex.chat_delivery",mode);
    }
    private static String requestsToggleLabel(LegacyConfig c){
        return I18n.format("screen.nyanlex.requests.toggle",c.translationRequestsEnabled?"OFF":"ON");
    }
    @Override public void render(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"NyanLex",width/2,16,0xFFFFFF);
        super.render(x,y,d);
    }
    @Override public void onGuiClosed(){
        NyanLexForge.save();
    }
}
