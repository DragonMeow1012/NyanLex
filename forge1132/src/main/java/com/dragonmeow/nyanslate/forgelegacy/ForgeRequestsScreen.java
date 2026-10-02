package com.dragonmeow.nyanslate.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Do-not-translate terms filter (1.0.7) for Forge 1.13.2. */
final class ForgeRequestsScreen extends GuiScreen implements ForgeButton.Handler{
    private static final int FIELD_W=310,TERMS_MAX_LENGTH=8000,LINE_H=10;
    /** Term separators: ASCII comma, full-width comma (U+FF0C), ideographic comma (U+3001), newline. */
    private static final String TERM_SEPARATORS="[,\uFF0C\u3001\\n]";
    private final GuiScreen parent;
    private GuiTextField terms;
    /** Field text as first shown; unchanged text is not re-parsed, so hand-edited terms survive. */
    private String shownTerms;
    private List<String> termsHint=Collections.emptyList();
    private int termsLabelY,termsHintY;
    ForgeRequestsScreen(GuiScreen p){
        parent=p;
    }
    @Override protected void initGui(){
        LegacyConfig c=NyanslateForge.config();
        int x=width/2-FIELD_W/2;
        // initGui() re-runs on window resize: keep what the user typed.
        String draft=terms==null?formatTerms(c.doNotTranslateTerms):terms.getText();
        termsLabelY=40;
        terms=new GuiTextField(20,fontRenderer,x,termsLabelY+11,FIELD_W,20);
        terms.setMaxStringLength(TERMS_MAX_LENGTH);
        terms.setText(draft);
        if(shownTerms==null)shownTerms=terms.getText();
        termsHintY=terms.y+26;
        termsHint=fontRenderer.listFormattedStringToWidth(I18n.format("screen.nyanslate.requests.terms.hint"),FIELD_W);
        addButton(new ForgeButton(0,width/2-100,height-26,200,20,I18n.format("gui.done"),this));
    }
    @Override public void onForgeButton(GuiButton b){
        if(b.id==0){
            applyTerms();
            NyanslateForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
    }
    /** Applies the typed terms to the live config (the client tick picks the change up). */
    private void applyTerms(){
        if(terms==null)return;
        String value=terms.getText();
        if(value.equals(shownTerms))return;
        NyanslateForge.config().doNotTranslateTerms=parseTerms(value);
        shownTerms=value;
    }
    /** Split on the term separators, then trim, drop blanks, case-insensitive de-duplication. */
    static List<String> parseTerms(String raw){
        List<String> parts=new ArrayList<String>();
        if(raw!=null)Collections.addAll(parts,raw.split(TERM_SEPARATORS));
        return LegacyConfig.normalizeDoNotTranslateTerms(parts);
    }
    static String formatTerms(List<String> list){
        StringBuilder out=new StringBuilder();
        for(String term:LegacyConfig.normalizeDoNotTranslateTerms(list)){
            if(out.length()>0)out.append(", ");
            out.append(term);
        }
        return out.toString();
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(terms!=null&&terms.keyPressed(key,scan,mods))return true;
        return super.keyPressed(key,scan,mods);
    }
    @Override public boolean charTyped(char ch,int mods){
        if(terms!=null&&terms.charTyped(ch,mods))return true;
        return super.charTyped(ch,mods);
    }
    @Override public boolean mouseClicked(double x,double y,int b){
        boolean handled=super.mouseClicked(x,y,b);
        if(terms!=null)handled|=terms.mouseClicked(x,y,b);
        return handled;
    }
    @Override public void tick(){
        if(terms!=null)terms.tick();
    }
    @Override public void render(int mx,int my,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.nyanslate.requests.title"),width/2,20,0xFFFFFF);
        int x=width/2-FIELD_W/2;
        fontRenderer.drawString(I18n.format("screen.nyanslate.requests.terms"),x,termsLabelY,0xA0A0A0);
        if(terms!=null)terms.drawTextField(mx,my,d);
        drawLines(termsHint,x,termsHintY);
        super.render(mx,my,d);
    }
    private void drawLines(List<String> lines,int x,int y){
        for(int i=0;i<lines.size();i++)fontRenderer.drawString(lines.get(i),x,y+i*LINE_H,0xA0A0A0);
    }
    @Override public void close(){
        applyTerms();
        NyanslateForge.save();
        mc.displayGuiScreen(parent);
    }
    @Override public void onGuiClosed(){
        applyTerms();
        NyanslateForge.save();
    }
}
