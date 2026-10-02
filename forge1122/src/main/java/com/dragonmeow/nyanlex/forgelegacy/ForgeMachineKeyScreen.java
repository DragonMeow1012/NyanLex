package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import java.io.IOException;
/**
 * API-key entry for the official machine-translation APIs (DeepL, Microsoft Translator), shown
 * after such a source is picked. The key is drawn as asterisks only; it stays in the local config
 * file and is sent only to the chosen provider.
 */
final class ForgeMachineKeyScreen extends GuiScreen{
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
    @Override public void initGui(){
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-130,y=height/2-40;
        // Input only: the box is never drawn, a masked copy is (see drawScreen).
        key=new GuiTextField(30,fontRenderer,x,y,260,20);
        key.setMaxStringLength(256);
        key.setText(microsoft()?c.microsoftApiKey:c.deeplApiKey);
        key.setFocused(true);
        if(microsoft()){
            region=new GuiTextField(31,fontRenderer,x,y+44,260,20);
            region.setMaxStringLength(64);
            region.setText(c.microsoftApiRegion);
        }
        addButton(new GuiButton(0,width/2-100,y+(microsoft()?84:40),200,20,I18n.format("gui.done")));
    }
    private void saveAndClose(){
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
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        if(b.id==0)saveAndClose();
    }
    @Override protected void keyTyped(char ch,int code)throws IOException{
        if(code==1){
            saveAndClose();
            return;
        }
        if(key.textboxKeyTyped(ch,code))return;
        if(region!=null&&region.textboxKeyTyped(ch,code))return;
        super.keyTyped(ch,code);
    }
    @Override protected void mouseClicked(int x,int y,int b)throws IOException{
        super.mouseClicked(x,y,b);
        key.mouseClicked(x,y,b);
        if(region!=null)region.mouseClicked(x,y,b);
    }
    @Override public void updateScreen(){
        key.updateCursorCounter();
        if(region!=null)region.updateCursorCounter();
    }
    @Override public void drawScreen(int mx,int my,float d){
        drawDefaultBackground();
        int x=width/2-130,y=height/2-40;
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.provider."+provider),width/2,y-40,0xFFFFFF);
        fontRenderer.drawString(I18n.format(microsoft()?"screen.nyanlex.provider.key_microsoft":"screen.nyanlex.provider.key_deepl"),x,y-12,0xA0A0A0);
        StringBuilder masked=new StringBuilder();
        for(int i=0;i<key.getText().length();i++)masked.append('*');
        if(key.isFocused()&&(Minecraft.getSystemTime()/500L)%2L==0L)masked.append('_');
        drawRect(x-1,y-1,x+261,y+21,key.isFocused()?0xFFFFFFFF:0xFFA0A0A0);
        drawRect(x,y,x+260,y+20,0xFF000000);
        fontRenderer.drawString(masked.toString(),x+4,y+6,0xE0E0E0);
        if(region!=null){
            fontRenderer.drawString(I18n.format("screen.nyanlex.provider.region"),x,y+32,0xA0A0A0);
            region.drawTextBox();
        }
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.provider.key_notice"),width/2,y+(microsoft()?112:68),0x909090);
        super.drawScreen(mx,my,d);
    }
    @Override public void onGuiClosed(){
        NyanLexForge.save();
    }
}
