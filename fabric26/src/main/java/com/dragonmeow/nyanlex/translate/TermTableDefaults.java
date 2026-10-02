package com.dragonmeow.nyanlex.translate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P1.7 shipped defaults for the SkyBlock rarity-line term table: the 12 rarity words
 * and the type words that follow them on an item's rarity line (e.g. the
 * {@code "GLOVES"} in {@code "EPIC DUNGEON GLOVES"}). Transcribed verbatim from the
 * user-approved {@code term-table-draft.json} (rarity + type sections). The
 * {@code reforge} section lives in its own {@link #REFORGE} map, used only by the
 * item-entity layer ({@link ItemEntityRegistry}) — never by the rarity-line composer,
 * so {@link #RARITY}/{@link #TYPE} stay exactly the P1.7 tables.
 *
 * <p>Kept as Java constants rather than a resource file because {@code sync-core.ps1}
 * mirrors only the Java packages across the 17 build trees, never
 * {@code src/main/resources}; a JSON/resource copy of this table would silently go
 * stale on every tree but the canonical one.</p>
 *
 * <p>{@link TermTableTest} asserts these maps stay in lock-step with the draft (a few
 * pinned key translations plus the total entry counts) since the test cannot read the
 * scratchpad draft file itself.</p>
 */
public final class TermTableDefaults {

    /** {@code RARITY.get("EPIC")} etc. — the fixed 12-word SkyBlock rarity ladder. */
    public static final Map<String, String> RARITY = rarityMap();

    /** {@code TYPE.get("DUNGEON")}, {@code TYPE.get("PET ITEM")} etc. — the type words
     *  (and the {@code SHINY} prefix) observed on a rarity line, per
     *  term-table-draft.md §3. Only type words actually seen on a rarity line are
     *  shipped; anything else is learned at runtime (P1.7 §3 of the design). */
    public static final Map<String, String> TYPE = typeMap();

    /** {@code REFORGE.get("Blended")} etc. — the 156 SkyBlock reforge display words
     *  (keys keep the in-game spelling, e.g. {@code "Blood-Soaked"},
     *  {@code "Deep Fried"}, {@code "Jerry's"}) and their approved zh_TW translations,
     *  from the {@code reforge} section of term-table-draft.json. Used only to translate
     *  a reforge the item's own data ({@code modifier}) has proven — never to guess. */
    public static final Map<String, String> REFORGE = reforgeMap();

    /** Upper-cased {@link #REFORGE} key -> the canonical key, for case-insensitive
     *  lookups (a display name's first word is compared case-insensitively). */
    private static final Map<String, String> REFORGE_KEY_BY_UPPER = reforgeKeysByUpper();

    private TermTableDefaults() {
    }

    /** The canonical {@link #REFORGE} key spelled like {@code word} ignoring case, or
     *  {@code null} when {@code word} is not a shipped reforge word. */
    public static String reforgeKey(String word) {
        if (word == null) return null;
        return REFORGE_KEY_BY_UPPER.get(word.strip().toUpperCase(java.util.Locale.ROOT));
    }

    private static Map<String, String> reforgeKeysByUpper() {
        Map<String, String> m = new java.util.HashMap<>();
        for (String key : REFORGE.keySet()) m.put(key.toUpperCase(java.util.Locale.ROOT), key);
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> rarityMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("COMMON", "普通");
        m.put("UNCOMMON", "罕見");
        m.put("RARE", "稀有");
        m.put("EPIC", "史詩");
        m.put("LEGENDARY", "傳奇");
        m.put("MYTHIC", "神話");
        m.put("DIVINE", "神聖");
        m.put("SPECIAL", "特殊");
        m.put("VERY SPECIAL", "非常特殊");
        m.put("ULTIMATE", "終極");
        m.put("SUPREME", "至高");
        m.put("ADMIN", "管理員");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> typeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DUNGEON", "地城");
        m.put("SHINY", "閃亮");
        m.put("ACCESSORY", "飾品");
        m.put("HELMET", "頭盔");
        m.put("CHESTPLATE", "胸甲");
        m.put("LEGGINGS", "護腿");
        m.put("BOOTS", "靴子");
        m.put("SWORD", "劍");
        m.put("LONGSWORD", "長劍");
        m.put("BOW", "弓");
        m.put("PET ITEM", "寵物物品");
        m.put("CLOAK", "披風");
        m.put("NECKLACE", "項鍊");
        m.put("GLOVES", "手套");
        m.put("BELT", "腰帶");
        m.put("BRACELET", "手環");
        m.put("GAUNTLET", "護手");
        m.put("HATCESSORY", "帽飾");
        m.put("COSMETIC", "外觀");
        m.put("DRILL", "鑽頭");
        m.put("WAND", "法杖");
        m.put("ROD PART", "釣竿部件");
        m.put("FISHING ROD", "釣竿");
        m.put("FISHING NET", "釣魚網");
        m.put("CARNIVAL MASK", "嘉年華面具");
        m.put("DEPLOYABLE", "部署物");
        m.put("AXE", "斧頭");
        m.put("PICKAXE", "鎬");
        m.put("SHOVEL", "鏟子");
        m.put("VACUUM", "吸塵器");
        m.put("DYE", "染料");
        m.put("ITEM", "物品");
        m.put("COMBAT SHARD", "戰鬥碎片");
        m.put("FOREST SHARD", "森林碎片");
        m.put("WATER SHARD", "水系碎片");
        m.put("TRAVEL SCROLL", "傳送卷軸");
        m.put("LASSO", "套索");
        m.put("FARMING TOOL", "農耕工具");
        m.put("TRAP", "陷阱");
        m.put("WATERING CAN", "澆水壺");
        m.put("REFORGE STONE", "重鑄石");
        m.put("POWER STONE", "力量石");
        m.put("ORE", "礦石");
        m.put("DWARVEN METAL", "矮人金屬");
        m.put("SACK", "麻袋");
        m.put("GARDEN CHIP", "花園晶片");
        m.put("GEMSTONE", "寶石");
        m.put("ARROW", "箭矢");
        m.put("ARROW POISON", "箭矢毒素");
        m.put("BAIT", "魚餌");
        m.put("SALT", "鹽");
        m.put("BLOCK", "方塊");
        m.put("MUTATION", "變異");
        m.put("RABBIT", "兔子");
        m.put("PORTAL", "傳送門");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, String> reforgeMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Ambered", "琥珀");
        m.put("Ancient", "遠古");
        m.put("Astute", "敏銳");
        m.put("Auspicious", "吉祥");
        m.put("Awkward", "笨拙");
        m.put("Beady", "珠珠");
        m.put("Bizarre", "怪異");
        m.put("Blazing", "熾熱");
        m.put("Blended", "混合");
        m.put("Blessed", "受祝福");
        m.put("Blood-Soaked", "血染");
        m.put("Bloodshot", "血紅");
        m.put("Bloody", "血腥");
        m.put("Blooming", "盛開");
        m.put("Bountiful", "豐饒");
        m.put("Brilliant", "燦爛");
        m.put("Bulky", "碩大");
        m.put("Bustling", "熱鬧");
        m.put("Buzzing", "嗡嗡");
        m.put("Calcified", "鈣化");
        m.put("Candied", "糖漬");
        m.put("Chomp", "啃咬");
        m.put("Clean", "潔淨");
        m.put("Coldfused", "冷熔");
        m.put("Colossal", "巨像");
        m.put("Cubic", "立方");
        m.put("Deadly", "致命");
        m.put("Deep Fried", "油炸");
        m.put("Demonic", "惡魔");
        m.put("Dimensional", "次元");
        m.put("Dirty", "骯髒");
        m.put("Double-Bit", "雙刃");
        m.put("Earthy", "泥土");
        m.put("Empowered", "賦能");
        m.put("Epic", "史詩");
        m.put("Excellent", "卓越");
        m.put("Fabled", "傳說");
        m.put("Fair", "公平");
        m.put("Fanged", "獠牙");
        m.put("Fast", "快速");
        m.put("Festive", "節慶");
        m.put("Fierce", "兇猛");
        m.put("Fine", "精良");
        m.put("Fleet", "疾速");
        m.put("Forceful", "強力");
        m.put("Fortified", "鞏固");
        m.put("Fortunate", "幸運");
        m.put("Fruitful", "豐收");
        m.put("Gentle", "溫和");
        m.put("Giant", "巨型");
        m.put("Gilded", "鍍金");
        m.put("Glacial", "冰川");
        m.put("Glistening", "閃耀");
        m.put("Godly", "神級");
        m.put("Grand", "宏偉");
        m.put("Great", "偉大");
        m.put("Greater Spook", "大驚嚇");
        m.put("Green Thumb", "綠手指");
        m.put("Groovy", "時髦");
        m.put("Hasty", "急速");
        m.put("Headstrong", "倔強");
        m.put("Heated", "加熱");
        m.put("Heavy", "重型");
        m.put("Hefty", "沉重");
        m.put("Heroic", "英雄");
        m.put("Honored", "榮耀");
        m.put("Hurtful", "傷人");
        m.put("Hyper", "超級");
        m.put("Itchy", "發癢");
        m.put("Jaded", "翡翠");
        m.put("Jerry's", "傑瑞的");
        m.put("Keen", "犀利");
        m.put("Legendary", "傳奇");
        m.put("Light", "輕盈");
        m.put("Loving", "摯愛");
        m.put("Lucky", "好運");
        m.put("Lumberjack's", "伐木工");
        m.put("Lunar", "月光");
        m.put("Lush", "繁茂");
        m.put("Lustrous", "光澤");
        m.put("Magnetic", "磁力");
        m.put("Majestic", "華麗");
        m.put("Mantid", "螳螂");
        m.put("Marshy", "沼澤");
        m.put("Menacing", "威脅");
        m.put("Mithraic", "密特拉");
        m.put("Moil", "苦幹");
        m.put("Moonglade", "月影");
        m.put("Mossy", "苔蘚");
        m.put("Mythic", "神話");
        m.put("Neat", "精緻");
        m.put("Necrotic", "死靈");
        m.put("Odd", "奇異");
        m.put("Ominous", "不祥");
        m.put("Overpriced", "天價");
        m.put("Peasant's", "農民");
        m.put("Perfect", "完美");
        m.put("Pitchin'", "投擲");
        m.put("Pleasant", "愉快");
        m.put("Precise", "精準");
        m.put("Pretty", "漂亮");
        m.put("Prospector's", "勘探者");
        m.put("Pure", "純淨");
        m.put("Rapid", "迅捷");
        m.put("Refined", "精煉");
        m.put("Reinforced", "加固");
        m.put("Renowned", "著名");
        m.put("Rich", "富裕");
        m.put("Ridiculous", "荒謬");
        m.put("Robust", "結實");
        m.put("Rooted", "根植");
        m.put("Royal", "皇家");
        m.put("Rugged", "粗獷");
        m.put("Salty", "鹹味");
        m.put("Scraped", "刮痕");
        m.put("Shaded", "陰影");
        m.put("Sharp", "銳利");
        m.put("Shiny", "閃亮");
        m.put("Silky", "絲滑");
        m.put("Simple", "簡單");
        m.put("Smart", "聰明");
        m.put("Snowy", "雪覆");
        m.put("Soft", "柔軟");
        m.put("Spicy", "辛辣");
        m.put("Spiked", "尖刺");
        m.put("Spiritual", "靈性");
        m.put("Squeaky", "吱吱");
        m.put("Stained", "染色");
        m.put("Stellar", "星辰");
        m.put("Sticky", "黏性");
        m.put("Stiff", "僵硬");
        m.put("Strange", "奇怪");
        m.put("Strengthened", "強化");
        m.put("Strong", "強壯");
        m.put("Sturdy", "堅固");
        m.put("Submerged", "沉沒");
        m.put("Sunny", "陽光");
        m.put("Superior", "優越");
        m.put("Suspicious", "可疑");
        m.put("Sweet", "甜美");
        m.put("Thorny", "荊棘");
        m.put("Titanic", "泰坦");
        m.put("Toil", "勞作");
        m.put("Trashy", "垃圾");
        m.put("Treacherous", "險惡");
        m.put("Undead", "不死");
        m.put("Unpleasant", "不快");
        m.put("Unreal", "非凡");
        m.put("Unyielding", "不屈");
        m.put("Vivid", "鮮明");
        m.put("Warped", "扭曲");
        m.put("Waxed", "上蠟");
        m.put("Wise", "智慧");
        m.put("Withered", "枯萎");
        m.put("Zealous", "狂熱");
        m.put("Zooming", "飛馳");
        return Collections.unmodifiableMap(m);
    }
}
