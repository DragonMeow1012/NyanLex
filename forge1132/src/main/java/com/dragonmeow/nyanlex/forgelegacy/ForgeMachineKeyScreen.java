package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
/**
 * API-key entry for the official machine-translation APIs (DeepL, Microsoft Translator), shown
 * after such a source is picked. The key is drawn as asterisks only; it stays in the local config
 * file and is sent only to the chosen provider.
 */
final class ForgeMachineKeyScreen extends GuiScreen implements ForgeButton.Handler{
    private final GuiScreen parent;
    private final String provider;
    private GuiTextField key,region;
    ForgeMachineKeyScreen(GuiScreen p,String provider){
        parent=p;
        this.provider=provider;
    }
    private boolean microsoft(){
        return "microsoft_api".equals(provider);
    }
    @Override protected void initGui(){
        buttons.clear();
        children.clear();
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-130,y=height/2-40;
        // Input only: the box is never drawn, a masked copy is (see render).
        key=new GuiTextField(30,fontRenderer,x,y,260,20);
        key.setMaxStringLength(256);
        key.setText(microsoft()?c.microsoftApiKey:c.deeplApiKey);
        key.setFocused(true);
        region=null;
        if(microsoft()){
            region=new GuiTextField(31,fontRenderer,x,y+44,260,20);
            region.setMaxStringLength(64);
            region.setText(c.microsoftApiRegion);
        }
        addButton(new ForgeButton(0,width/2-100,y+(microsoft()?84:40),200,20,I18n.format("gui.done"),this));
    }
    @Override public void onForgeButton(GuiButton b){
        if(b.id==0)close();
    }
    @Override public boolean keyPressed(int code,int scan,int mods){
        if(key!=null&&key.keyPressed(code,scan,mods))return true;
        if(region!=null&&region.keyPressed(code,scan,mods))return true;
        return super.keyPressed(code,scan,mods);
    }
    @Override public boolean charTyped(char ch,int mods){
        if(key!=null&&key.charTyped(ch,mods))return true;
        if(region!=null&&region.charTyped(ch,mods))return true;
        return super.charTyped(ch,mods);
    }
    @Override public boolean mouseClicked(double x,double y,int b){
        boolean handled=super.mouseClicked(x,y,b);
        if(key!=null)handled|=key.mouseClicked(x,y,b);
        if(region!=null)handled|=region.mouseClicked(x,y,b);
        return handled;
    }
    @Override public void tick(){
        if(key!=null)key.tick();
        if(region!=null)region.tick();
    }
    @Override public void render(int mx,int my,float d){
        drawDefaultBackground();
        int x=width/2-130,y=height/2-40;
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.provider."+provider),width/2,y-40,0xFFFFFF);
        fontRenderer.drawString(I18n.format(microsoft()?"screen.nyanlex.provider.key_microsoft":"screen.nyanlex.provider.key_deepl"),x,y-12,0xA0A0A0);
        StringBuilder masked=new StringBuilder();
        for(int i=0;i<key.getText().length();i++)masked.append('*');
        if(key.isFocused()&&(System.currentTimeMillis()/500L)%2L==0L)masked.append('_');
        drawRect(x-1,y-1,x+261,y+21,key.isFocused()?0xFFFFFFFF:0xFFA0A0A0);
        drawRect(x,y,x+260,y+20,0xFF000000);
        fontRenderer.drawString(masked.toString(),x+4,y+6,0xE0E0E0);
        if(region!=null){
            fontRenderer.drawString(I18n.format("screen.nyanlex.provider.region"),x,y+32,0xA0A0A0);
            region.drawTextField(mx,my,d);
        }
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.provider.key_notice"),width/2,y+(microsoft()?112:68),0x909090);
        super.render(mx,my,d);
    }
    @Override public void close(){
        LegacyConfig c=NyanLexForge.config();
        String k=key.getText().trim();
        if(microsoft()){
            c.microsoftApiKey=k;
            c.microsoftApiRegion=region==null?"":region.getText().trim();
        }else{
            c.deeplApiKey=k;
        }
        NyanLexForge.save();
        mc.displayGuiScreen(parent);
    }
}
