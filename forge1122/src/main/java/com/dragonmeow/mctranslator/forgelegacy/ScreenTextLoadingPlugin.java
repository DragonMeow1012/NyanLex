package com.dragonmeow.mctranslator.forgelegacy;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("MinecraftTranslatorScreenText")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"com.dragonmeow.mctranslator.forgelegacy.ScreenTextTransformer", "com.dragonmeow.mctranslator.forgelegacy.ScreenTextLoadingPlugin"})
public final class ScreenTextLoadingPlugin implements IFMLLoadingPlugin {
    public String[] getASMTransformerClass() { return new String[]{"com.dragonmeow.mctranslator.forgelegacy.ScreenTextTransformer"}; }
    public String getModContainerClass() { return null; }
    public String getSetupClass() { return null; }
    public void injectData(Map<String,Object> data) { }
    public String getAccessTransformerClass() { return null; }
}
