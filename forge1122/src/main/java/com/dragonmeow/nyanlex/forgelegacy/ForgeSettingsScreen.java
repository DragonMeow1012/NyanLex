package com.dragonmeow.nyanlex.forgelegacy;
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
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-155;
        // Prominent (but non-blocking) entry point to the help screen, top-left corner.
        addButton(new GuiButton(16,6,4,70,14,"§e"+I18n.format("config.nyanlex.help.open")));
        addButton(new GuiButton(1,x,30,310,20,c.followGameLanguage?"Language: Game ("+NyanLexForge.currentTarget()+")":"Language: "+c.targetLang));
        addButton(new GuiButton(2,x,50,152,20,c.enabled?"Translator: ON":"Translator: OFF"));
        addButton(new GuiButton(14,x+158,50,152,20,I18n.format("config.nyanlex.requests.open")));
        addButton(new GuiButton(3,x,70,310,20,c.showOriginal?"Original + Translation":"Translation Only"));
        addButton(new GuiButton(4,x,90,152,20,"Engine: "+(c.aiEnabled?"AI":"Machine")));
        addButton(new GuiButton(5,x+158,90,152,20,machineFallbackLabel(c)));
        addButton(new GuiButton(6,x,110,310,20,I18n.format("screen.nyanlex.ai.title")));
        // Request cooldown + batch window merged into one submenu (was two half-width cyclers).
        addButton(new GuiButton(7,x,130,310,20,I18n.format("config.nyanlex.request_cooldown.open")));
        addButton(new GuiButton(9,x,150,152,20,"Machine: "+LegacyConfig.normalizeMachineProvider(c.machineTranslationProvider)));
        addButton(new GuiButton(10,x+158,150,152,20,"Debug HUD: "+(c.debugTranslationOverlay?"ON":"OFF")));
        addButton(new GuiButton(11,x,170,310,20,chatDeliveryLabel(c)));
        addButton(new GuiButton(15,x,height-46,310,20,requestsToggleLabel(c)));
        addButton(new GuiButton(12,x,height-22,100,20,I18n.format("config.nyanlex.translations.export")));
        addButton(new GuiButton(13,x+105,height-22,100,20,I18n.format("config.nyanlex.translations.import")));
        addButton(new GuiButton(0,x+210,height-22,100,20,I18n.format("gui.done")));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
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
            c.machineTranslationProvider="google".equals(p)?"deepl_api":"deepl_api".equals(p)?"microsoft_api":"google";
            // Official APIs need the player's own key: ask for it right after the pick.
            if(!"google".equals(c.machineTranslationProvider)){
                NyanLexForge.save();
                mc.displayGuiScreen(new ForgeMachineKeyScreen(this,c.machineTranslationProvider));
                return;
            }
        }
        if(b.id==10){
            c.debugTranslationOverlay=!c.debugTranslationOverlay;
            if(!c.debugTranslationOverlay)NyanLexForge.TRANSLATOR.clearDebug();
        }
        if(b.id==11)c.deliverChatTranslationsInOrder=!c.deliverChatTranslationsInOrder;
        if(b.id==15)c.translationRequestsEnabled=!c.translationRequestsEnabled;
        buttonList.clear();
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
    @Override public void drawScreen(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"NyanLex",width/2,16,0xFFFFFF);
        super.drawScreen(x,y,d);
    }
    @Override public void onGuiClosed(){
        NyanLexForge.save();
    }
}
