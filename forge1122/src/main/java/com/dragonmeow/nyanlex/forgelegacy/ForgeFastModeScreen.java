package com.dragonmeow.nyanlex.forgelegacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import java.util.List;
import java.util.function.Consumer;

/** Explicit premium-speed consent; cancel never changes the saved preference. */
final class ForgeFastModeScreen extends GuiScreen {
    private final GuiScreen parent;
    private final Consumer<Boolean> onConfirm;
    private boolean remember;
    private List<String> lines;
    private int top;
    ForgeFastModeScreen(GuiScreen parent, Consumer<Boolean> onConfirm) {
        this.parent=parent;
        this.onConfirm=onConfirm;
    }
    @Override public void initGui() {
        buttonList.clear();
        lines=fontRenderer.listFormattedStringToWidth(I18n.format("screen.nyanlex.ai.fast.message"),Math.min(360,width-24));
        top=Math.max(8,(height-(lines.size()*10+80))/2);
        int row=top+24+lines.size()*10;
        addButton(new GuiButton(2,width/2-150,row,300,20,(remember?"[x] ":"[ ] ")+I18n.format("screen.nyanlex.notice.dont_show_again")));
        addButton(new GuiButton(1,width/2-150,row+26,147,20,I18n.format("gui.cancel")));
        addButton(new GuiButton(0,width/2+3,row+26,147,20,I18n.format("screen.nyanlex.ai.fast.enable")));
    }
    @Override protected void actionPerformed(GuiButton b) {
        if(b.id==2){remember=!remember;initGui();return;}
        if(b.id==0)onConfirm.accept(remember);
        mc.displayGuiScreen(parent);
    }
    @Override public void drawScreen(int x,int y,float delta) {
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.ai.fast.title"),width/2,top,0xFFFFFF);
        for(int i=0;i<lines.size();i++)drawCenteredString(fontRenderer,lines.get(i),width/2,top+18+i*10,0xDDDDDD);
        super.drawScreen(x,y,delta);
    }
    @Override protected void keyTyped(char ch,int code) throws java.io.IOException { if(code==1)mc.displayGuiScreen(parent);else super.keyTyped(ch,code); }
}
