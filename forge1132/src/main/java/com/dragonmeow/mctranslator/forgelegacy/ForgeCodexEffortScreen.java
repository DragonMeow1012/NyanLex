package com.dragonmeow.mctranslator.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
final class ForgeCodexEffortScreen extends GuiScreen implements ForgeButton.Handler{
    private final GuiScreen parent;
    private List<String> efforts=Collections.emptyList();
    private int selected=-1;
    private long lastClick;
    ForgeCodexEffortScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        LegacyCodexClient c=MinecraftTranslatorForge.codexClient();
        if(c!=null)for(LegacyCodexClient.ModelOption o:c.cachedModels())if(o.model().equals(MinecraftTranslatorForge.config().codexModel))efforts=o.reasoningEfforts();
        int y=40;
        for(int i=0;i<efforts.size();i++){
            if(efforts.get(i).equals(MinecraftTranslatorForge.config().codexReasoningEffort))selected=i;
            addButton(new ForgeButton(100+i,width/2-120,y+i*24,240,20,(i==selected?"[":"")+efforts.get(i)+(i==selected?"]":"") ,this));
        }
        addButton(new ForgeButton(0,width/2-100,height-28,200,20,"Done" ,this));
    }
    @Override public void onForgeButton(GuiButton b){
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
            buttons.clear();
            children.clear();
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
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==257||key==335){
            apply();
            return true;
        }
        return super.keyPressed(key,scan,mods);
    }
    @Override public void render(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"Choose reasoning effort",width/2,16,0xFFFFFF);
        super.render(x,y,d);
    }
    @Override public void close(){
        mc.displayGuiScreen(parent);
    }
    @Override public void onGuiClosed(){
    }
}
