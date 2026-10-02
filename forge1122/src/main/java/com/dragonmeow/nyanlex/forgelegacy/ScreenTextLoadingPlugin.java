package com.dragonmeow.nyanlex.forgelegacy;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("NyanLexScreenText")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"com.dragonmeow.nyanlex.forgelegacy.ScreenTextTransformer", "com.dragonmeow.nyanlex.forgelegacy.ScreenTextLoadingPlugin"})
public final class ScreenTextLoadingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return new String[]{"com.dragonmeow.nyanlex.forgelegacy.ScreenTextTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String,Object> data) { }
    public String getAccessTransformerClass() { return null; }
}
