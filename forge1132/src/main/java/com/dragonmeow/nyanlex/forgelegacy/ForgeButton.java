package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
final class ForgeButton extends GuiButton {
    interface Handler {
        void onForgeButton(GuiButton button);
    }
    private final Handler handler;
    ForgeButton(int id,int x,int y,int width,int height,String label,Handler handler){
        super(id,x,y,width,height,label);
        this.handler=handler;
    }
    @Override public void onClick(double mouseX,double mouseY){
        handler.onForgeButton(this);
    }
}
