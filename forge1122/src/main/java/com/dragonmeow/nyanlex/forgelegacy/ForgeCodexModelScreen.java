package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
final class ForgeCodexModelScreen extends GuiScreen{
    private final GuiScreen parent;
    private GuiTextField search;
    private final List<LegacyCodexClient.ModelOption> shown=new ArrayList<LegacyCodexClient.ModelOption>();
    private int page,selected=-1;
    private long lastClick;
    ForgeCodexModelScreen(GuiScreen p){
        parent=p;
    }
    @Override public void initGui(){
        search=new GuiTextField(1,fontRenderer,width/2-120,24,240,20);
        rebuild();
    }
    private void rebuild(){
        String q=search==null?"":search.getText().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        LegacyCodexClient c=NyanLexForge.codexClient();
        if(c!=null)for(LegacyCodexClient.ModelOption o:c.cachedModels())if(q.isEmpty()||o.model().toLowerCase(Locale.ROOT).contains(q)||o.displayName().toLowerCase(Locale.ROOT).contains(q))shown.add(o);
        int pages=Math.max(1,(shown.size()+7)/8);
        if(page>=pages)page=pages-1;
        buttonList.clear();
        for(int i=0;i<8;i++){
            int n=page*8+i;
            if(n>=shown.size())break;
            LegacyCodexClient.ModelOption o=shown.get(n);
            addButton(new GuiButton(100+i,width/2-150,52+i*22,300,20,(n==selected?"[":"")+o.displayName()+(!o.displayName().equals(o.model())?" ("+o.model()+")":"")+(n==selected?"]":"")));
        }
        addButton(new GuiButton(10,width/2-150,height-48,72,20,"<"));
        addButton(new GuiButton(11,width/2-72,height-48,144,20,(page+1)+" / "+pages));
        addButton(new GuiButton(12,width/2+78,height-48,72,20,">"));
        addButton(new GuiButton(0,width/2-100,height-24,200,20,"Done"));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        if(b.id==0){
            apply();
            return;
        }
        if(b.id==10&&page>0){
            page--;
            rebuild();
        }
        if(b.id==12&&(page+1)*8<shown.size()){
            page++;
            rebuild();
        }
        if(b.id>=100&&b.id<108){
            int n=page*8+b.id-100;
            long now=System.currentTimeMillis();
            if(selected==n&&now-lastClick<300){
                selected=n;
                apply();
                return;
            }
            selected=n;
            lastClick=now;
            rebuild();
        }
    }
    private void apply(){
        if(selected>=0&&selected<shown.size()){
            LegacyCodexClient.ModelOption o=shown.get(selected);
            LegacyConfig c=NyanLexForge.config();
            c.codexModel=o.model();
            ForgeAiConfigScreen.normalizeEffort(c,o);
            NyanLexForge.save();
        }
        mc.displayGuiScreen(parent);
    }
    @Override protected void keyTyped(char ch,int code)throws IOException{
        if(code==1){
            mc.displayGuiScreen(parent);
            return;
        }
        String before=search.getText();
        if(search.textboxKeyTyped(ch,code)){
            if(code==28||code==156){
                apply();
                return;
            }
            if(!before.equals(search.getText())){
                page=0;
                selected=-1;
                rebuild();
            }
            return;
        }
        super.keyTyped(ch,code);
    }
    @Override protected void mouseClicked(int x,int y,int b)throws IOException{
        super.mouseClicked(x,y,b);
        search.mouseClicked(x,y,b);
    }
    @Override public void updateScreen(){
        search.updateCursorCounter();
    }
    @Override public void drawScreen(int x,int y,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,"Choose Codex model",width/2,8,0xFFFFFF);
        search.drawTextBox();
        super.drawScreen(x,y,d);
    }
    @Override public void onGuiClosed(){
    }
}
