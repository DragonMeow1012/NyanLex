package com.dragonmeow.mctranslator.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
final class ForgeCodexEffortScreen extends GuiScreen{
    private final GuiScreen parent;
    private List<String> efforts=Collections.emptyList();
    private int selected=-1;
    private long lastClick;
    ForgeCodexEffortScreen(GuiScreen p){
        parent=p;
    }
    @Override public void initGui(){
        LegacyCodexClient c=MinecraftTranslatorForge.codexClient();
        if(c!=null)for(LegacyCodexClient.ModelOption o:c.cachedModels())if(o.model().equals(MinecraftTranslatorForge.config().codexModel))efforts=o.reasoningEfforts();
        int y=40;
        for(int i=0;i<efforts.size();i++){
            if(efforts.get(i).equals(MinecraftTranslatorForge.config().codexReasoningEffort))selected=i;
            addButton(new GuiButton(100+i,width/2-120,y+i*24,240,20,(i==selected?"[":"")+efforts.get(i)+(i==selected?"]":"")));
        }
        addButton(new GuiButton(0,width/2-100,height-28,200,20,"Done"));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        if(b.id==0){
            apply();
            return;
        }
        if(b.id>=100){
            int n=b.id-100;
            long now=System.currentTimeMillis();
            if(selected==n&&now-lastClick<300){
                selected=n;
                apply();
                return;
            }
            selected=n;
            lastClick=now;
            buttonList.clear();
            initGui();
        }
    }
    private void apply(){
        if(selected>=0&&selected<efforts.size()){
            MinecraftTranslatorForge.config().codexReasoningEffort=efforts.get(selected);
            MinecraftTranslatorForge.save();
        }
        mc.displayGuiScreen(parent);
    }
    @Override protected void keyTyped(char ch,int code)throws IOException{
        if(code==1){
            mc.displayGuiScreen(parent);
            return;
        }
        if(code==28||code==156){
            apply();
            return;
        }
        super.keyTyped(ch,code);
    }
    @Override public void drawScreen(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"Choose reasoning effort",width/2,16,0xFFFFFF);
        super.drawScreen(x,y,d);
    }
    @Override public void onGuiClosed(){
    }
}
