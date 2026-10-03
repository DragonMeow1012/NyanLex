package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
/**
 * 1.0.7 UI round 3: "Request Cooldown Settings..." submenu. Holds the two cyclers that used
 * to share a row on the main settings screen (request cooldown, batch collection window).
 * Save behaviour is unchanged; only the layout moved.
 */
final class ForgeCooldownScreen extends GuiScreen implements ForgeButton.Handler{
    private final GuiScreen parent;
    ForgeCooldownScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-155;
        addButton(new ForgeButton(1,x,50,310,20,"Cooldown: "+(c.requestCooldownMs<=0?"OFF":c.requestCooldownMs+" ms"),this));
        addButton(new ForgeButton(2,x,74,310,20,"Batch: "+(c.batchWindowMs<=0?"OFF":c.batchWindowMs/1000F+" s"),this));
        addButton(new ForgeButton(0,width/2-100,height-26,200,20,I18n.format("gui.done"),this));
    }
    @Override public void onForgeButton(GuiButton b){
        LegacyConfig c=NyanLexForge.config();
        if(b.id==0){
            NyanLexForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(b.id==1)c.requestCooldownMs=ForgeSettingsScreen.next(c.requestCooldownMs,new int[]{
            0,1000,2000,3000,4000,5000,6000,7000,8000,9000,10000
        });
        if(b.id==2)c.batchWindowMs=ForgeSettingsScreen.next(c.batchWindowMs,new int[]{
            0,1000,2000,3000,4000,5000,6000,7000,8000,9000,10000
        });
        buttons.clear();
        children.clear();
        initGui();
    }
    @Override public void render(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.cooldown.title"),width/2,20,0xFFFFFF);
        super.render(x,y,d);
    }
    @Override public void onGuiClosed(){
        NyanLexForge.save();
    }
}
