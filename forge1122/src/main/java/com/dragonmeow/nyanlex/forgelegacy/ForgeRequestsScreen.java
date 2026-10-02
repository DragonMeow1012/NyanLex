package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Do-not-translate terms filter (1.0.7) for Forge 1.12.2. */
final class ForgeRequestsScreen extends GuiScreen{
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
    @Override public void initGui(){
        LegacyConfig c=NyanLexForge.config();
        int x=width/2-FIELD_W/2;
        // initGui() re-runs on window resize: keep what the user typed.
        String draft=terms==null?formatTerms(c.doNotTranslateTerms):terms.getText();
        termsLabelY=40;
        terms=new GuiTextField(20,fontRenderer,x,termsLabelY+11,FIELD_W,20);
        terms.setMaxStringLength(TERMS_MAX_LENGTH);
        terms.setText(draft);
        if(shownTerms==null)shownTerms=terms.getText();
        termsHintY=terms.y+26;
        termsHint=fontRenderer.listFormattedStringToWidth(I18n.format("screen.nyanlex.requests.terms.hint"),FIELD_W);
        addButton(new GuiButton(0,width/2-100,height-26,200,20,I18n.format("gui.done")));
    }
    @Override protected void actionPerformed(GuiButton b)throws IOException{
        if(b.id==0){
            applyTerms();
            NyanLexForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
    }
    /** Applies the typed terms to the live config (the client tick picks the change up). */
    private void applyTerms(){
        if(terms==null)return;
        String value=terms.getText();
        if(value.equals(shownTerms))return;
        NyanLexForge.config().doNotTranslateTerms=parseTerms(value);
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
    @Override protected void keyTyped(char ch,int code)throws IOException{
        if(code==1){
            applyTerms();
            NyanLexForge.save();
            mc.displayGuiScreen(parent);
            return;
        }
        if(terms!=null&&terms.textboxKeyTyped(ch,code))return;
        super.keyTyped(ch,code);
    }
    @Override protected void mouseClicked(int x,int y,int b)throws IOException{
        super.mouseClicked(x,y,b);
        if(terms!=null)terms.mouseClicked(x,y,b);
    }
    @Override public void updateScreen(){
        if(terms!=null)terms.updateCursorCounter();
    }
    @Override public void drawScreen(int mx,int my,float d){
        drawDefaultBackground();
        drawCenteredString(fontRenderer,I18n.format("screen.nyanlex.requests.title"),width/2,20,0xFFFFFF);
        int x=width/2-FIELD_W/2;
        fontRenderer.drawString(I18n.format("screen.nyanlex.requests.terms"),x,termsLabelY,0xA0A0A0);
        if(terms!=null)terms.drawTextBox();
        drawLines(termsHint,x,termsHintY);
        super.drawScreen(mx,my,d);
    }
    private void drawLines(List<String> lines,int x,int y){
        for(int i=0;i<lines.size();i++)fontRenderer.drawString(lines.get(i),x,y+i*LINE_H,0xA0A0A0);
    }
    @Override public void onGuiClosed(){
        applyTerms();
        NyanLexForge.save();
    }
}
