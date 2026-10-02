package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import java.util.Collections;
import java.util.List;
/**
 * 1.0.7 UI round 3: "? Help" — a short, non-blocking reference for first-time players.
 * Paginated (not scrollable) to keep the implementation trivial on this old GUI API.
 */
final class ForgeHelpScreen extends GuiScreen implements ForgeButton.Handler{
    private static final int MAX_CONTENT_W=300,LINE_H=10;
    private final GuiScreen parent;
    private List<String> lines=Collections.emptyList();
    private int linesPerPage=1,totalPages=1,page;
    private int contentX,topY,navY;
    ForgeHelpScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        int contentW=Math.max(160,Math.min(MAX_CONTENT_W,width-40));
        contentX=width/2-contentW/2;
        lines=fontRenderer.listFormattedStringToWidth(I18n.format("screen.nyanlex.help.body"),contentW);
        navY=height-52;
        int doneY=height-26;
        topY=50;
        int available=Math.max(LINE_H,navY-8-topY);
        linesPerPage=Math.max(1,available/LINE_H);
        totalPages=Math.max(1,(int)Math.ceil(lines.size()/(double)linesPerPage));
        if(page>=totalPages)page=totalPages-1;
        if(page<0)page=0;
        addButton(new ForgeButton(3,contentX,26,contentW,18,I18n.format("screen.nyanlex.help.quick"),this));
        addButton(new ForgeButton(1,contentX,navY,40,20,"<",this));
        addButton(new ForgeButton(2,contentX+contentW-40,navY,40,20,">",this));
        int doneW=Math.min(200,contentW);
        addButton(new ForgeButton(0,width/2-doneW/2,doneY,doneW,20,I18n.format("gui.done"),this));
    }
    @Override public void onForgeButton(GuiButton b){
        if(b.id==0){
            mc.displayGuiScreen(parent);
            return;
        }
        if(b.id==3){
            mc.displayGuiScreen(new ForgeSetupScreen(this,true));
            return;
        }
        if(b.id==1&&page>0)page--;
        if(b.id==2&&page<totalPages-1)page++;
        buttons.clear();
        children.clear();
        initGui();
    }
    @Override public void render(int mx,int my,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.help.title"),width/2,12,0xFFFFFF);
        int start=page*linesPerPage;
        int end=Math.min(lines.size(),start+linesPerPage);
        int y=topY;
        for(int i=start;i<end;i++){
            fontRenderer.drawStringWithShadow(lines.get(i),contentX,y,0xE0E0E0);
            y+=LINE_H;
        }
        String pageLabel=(page+1)+" / "+totalPages;
        drawCenteredString(fontRenderer,pageLabel,width/2,navY+6,0xA0A0A0);
        super.render(mx,my,d);
    }
    @Override public void onGuiClosed(){}
}
