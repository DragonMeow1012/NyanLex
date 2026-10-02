package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiScreenRealmsProxy;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.IRecipeShownListener;
import net.minecraft.realms.RealmsEditBox;
import net.minecraft.realms.RealmsScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
/**
 * "Is the player typing?" for the mod hotkeys (MC 1.12.2): true while the current screen has a focused
 * text input, so G / H / P never fire from keys meant for it. Covers chat, sign editing and book-and-quill
 * editing (keyTyped, no GuiTextField; a signed book being read is not typing), any focused GuiTextField
 * held by the screen (anvil, creative search, command block, other mods; 1.12.2 feeds keys to a focused
 * field whether or not it is visible) or by a row of a GuiListExtended the screen holds (Forge config
 * screens GuiConfig / GuiEditArray), a focused RealmsEditBox of a Realms screen (keys fed by hand) and the
 * recipe-book search while the book is open. Never throws: a mod screen that breaks the lookup (e.g. a
 * field whose optional type is missing) only loses that part of the check.
 */
final class ForgeTextInput{
    /** Rows looked at per list: config lists have tens; the cap bounds a key press on a huge list. */
    private static final int MAX_LIST_ROWS=2048;
    private ForgeTextInput(){
    }
    static boolean focused(GuiScreen screen){
        if(screen==null)return false;
        if(screen instanceof GuiChat||screen instanceof GuiEditSign)return true;
        if(screen instanceof GuiScreenBook&&editableBook((GuiScreenBook)screen))return true;
        return recipeSearchFocused(screen)||realmsBoxFocused(screen)||hasFocusedField(screen,true);
    }
    /** GuiScreenBook's only final boolean is bookIsUnsigned (book and quill); matched by shape, not name. */
    private static boolean editableBook(GuiScreenBook book){
        try{
            Field flag=null;
            for(Field field:GuiScreenBook.class.getDeclaredFields()){
                int mods=field.getModifiers();
                if(field.getType()!=boolean.class||Modifier.isStatic(mods)||!Modifier.isFinal(mods))continue;
                if(flag!=null)return true;
                flag=field;
            }
            if(flag==null)return true;
            flag.setAccessible(true);
            return flag.getBoolean(book);
        }catch(ReflectiveOperationException|LinkageError|RuntimeException unreadable){
            return true;
        }
    }
    private static boolean recipeSearchFocused(GuiScreen screen){
        if(!(screen instanceof IRecipeShownListener))return false;
        try{
            GuiRecipeBook book=((IRecipeShownListener)screen).func_194310_f();
            return book!=null&&book.isVisible()&&hasFocusedField(book,false);
        }catch(LinkageError|RuntimeException notInitialised){
            return false;
        }
    }
    /** Realms screens sit behind a proxy and keep RealmsEditBox fields (GuiTextField inside). */
    private static boolean realmsBoxFocused(GuiScreen screen){
        if(!(screen instanceof GuiScreenRealmsProxy))return false;
        try{
            RealmsScreen realms=((GuiScreenRealmsProxy)screen).getProxy();
            return realms!=null&&hasFocusedField(realms,false);
        }catch(LinkageError|RuntimeException broken){
            return false;
        }
    }
    /** 1.12.2 GuiTextField.textboxKeyTyped takes every key once the field is focused. */
    private static boolean focusedInput(Object value){
        if(value instanceof GuiTextField)return ((GuiTextField)value).isFocused();
        return value instanceof RealmsEditBox&&((RealmsEditBox)value).isFocused();
    }
    /** Field scan by type, so it works with MCP (dev) and SRG (production) names alike. */
    private static boolean hasFocusedField(Object owner,boolean listRows){
        for(Class<?> type=owner.getClass();type!=null&&type!=Object.class;type=type.getSuperclass()){
            Field[] fields;
            try{
                fields=type.getDeclaredFields();
            }catch(LinkageError|RuntimeException unloadable){
                continue; // e.g. a field typed with an absent optional dependency; superclasses still count
            }
            for(Field field:fields){
                try{
                    if(Modifier.isStatic(field.getModifiers()))continue;
                    Class<?> kind=field.getType();
                    boolean input=GuiTextField.class.isAssignableFrom(kind)||RealmsEditBox.class.isAssignableFrom(kind);
                    if(!input&&!(listRows&&GuiListExtended.class.isAssignableFrom(kind)))continue;
                    field.setAccessible(true);
                    Object value=field.get(owner);
                    if(input?focusedInput(value):value!=null&&focusedRow(value))return true;
                }catch(ReflectiveOperationException|LinkageError|RuntimeException unreadable){
                    // unreadable field: not evidence of typing
                }
            }
        }
        return false;
    }
    /** Rows of a GuiListExtended (GuiConfigEntries.listEntries, ...) found by type: any Iterable of list entries. */
    private static boolean focusedRow(Object list){
        for(Class<?> type=list.getClass();type!=null&&type!=Object.class;type=type.getSuperclass()){
            Field[] fields;
            try{
                fields=type.getDeclaredFields();
            }catch(LinkageError|RuntimeException unloadable){
                continue;
            }
            for(Field field:fields){
                try{
                    if(Modifier.isStatic(field.getModifiers())||!Iterable.class.isAssignableFrom(field.getType()))continue;
                    field.setAccessible(true);
                    Object rows=field.get(list);
                    if(rows==null)continue;
                    int seen=0;
                    for(Object row:(Iterable<?>)rows){
                        if(++seen>MAX_LIST_ROWS)break;
                        if(row instanceof GuiListExtended.IGuiListEntry&&hasFocusedField(row,false))return true;
                    }
                }catch(ReflectiveOperationException|LinkageError|RuntimeException unreadable){
                    // unreadable rows: not evidence of typing
                }
            }
        }
        return false;
    }
}
