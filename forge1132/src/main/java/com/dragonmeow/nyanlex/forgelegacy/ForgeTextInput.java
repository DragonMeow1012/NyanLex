package com.dragonmeow.nyanlex.forgelegacy;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenBook;
import net.minecraft.client.gui.GuiScreenRealmsProxy;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.IGuiEventListenerDeferred;
import net.minecraft.client.gui.inventory.GuiEditSign;
import net.minecraft.client.gui.recipebook.GuiRecipeBook;
import net.minecraft.client.gui.recipebook.IRecipeShownListener;
import net.minecraft.realms.RealmsEditBox;
import net.minecraft.realms.RealmsScreen;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
/**
 * "Is the player typing?" for the mod hotkeys (MC 1.13.2): true while the current screen has a focused
 * text input, so G / H / P never fire from keys meant for it. Covers chat, sign editing and book-and-quill
 * editing (no GuiTextField; a signed book being read is not typing), a visible focused GuiTextField on the
 * focus chain (screen focus, nested containers) or in a field of the screen (anvil, creative search,
 * command block, other mods), a Realms screen's RealmsEditBox and the recipe-book search while the book
 * is open (the screen focus is the GuiRecipeBook). Never throws: a mod screen that breaks the lookup
 * (e.g. a field whose optional type is missing) only loses that part of the check.
 */
final class ForgeTextInput{
    /** Screen, container, widget is the usual depth; the cap also ends a self-referencing chain. */
    private static final int MAX_FOCUS_DEPTH=8;
    private ForgeTextInput(){
    }
    static boolean focused(GuiScreen screen){
        if(screen==null)return false;
        if(screen instanceof GuiChat||screen instanceof GuiEditSign)return true;
        if(screen instanceof GuiScreenBook&&editableBook((GuiScreenBook)screen))return true;
        return focusChainHasField(screen)||recipeSearchFocused(screen)||realmsBoxFocused(screen)||hasFocusedField(screen);
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
    /** Keys go to getFocused() at every level, so follow that chain down to the widget that gets them. */
    private static boolean focusChainHasField(IGuiEventListenerDeferred container){
        try{
            IGuiEventListener focus=container.getFocused();
            for(int depth=0;focus!=null&&depth<MAX_FOCUS_DEPTH;depth++){
                if(focusedField(focus))return true;
                if(!(focus instanceof IGuiEventListenerDeferred))return false;
                focus=((IGuiEventListenerDeferred)focus).getFocused();
            }
        }catch(LinkageError|RuntimeException broken){
            // a failing mod widget is not evidence of typing
        }
        return false;
    }
    private static boolean recipeSearchFocused(GuiScreen screen){
        if(!(screen instanceof IRecipeShownListener))return false;
        try{
            GuiRecipeBook book=((IRecipeShownListener)screen).func_194310_f();
            return book!=null&&book.isVisible()&&hasFocusedField(book);
        }catch(LinkageError|RuntimeException notInitialised){
            return false;
        }
    }
    /** Realms screens sit behind a proxy and keep RealmsEditBox fields (GuiTextField inside). */
    private static boolean realmsBoxFocused(GuiScreen screen){
        if(!(screen instanceof GuiScreenRealmsProxy))return false;
        try{
            RealmsScreen realms=((GuiScreenRealmsProxy)screen).getProxy();
            return realms!=null&&hasFocusedField(realms);
        }catch(LinkageError|RuntimeException broken){
            return false;
        }
    }
    /** 1.13.2 GuiTextField.keyPressed/charTyped only act when the field is visible and focused. */
    private static boolean focusedField(Object value){
        if(value instanceof RealmsEditBox)value=((RealmsEditBox)value).getProxy();
        if(!(value instanceof GuiTextField))return false;
        GuiTextField field=(GuiTextField)value;
        return field.getVisible()&&field.isFocused();
    }
    /** Field scan by type, so it works with MCP (dev) and SRG (production) names alike. */
    private static boolean hasFocusedField(Object owner){
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
                    if(!GuiTextField.class.isAssignableFrom(kind)&&!RealmsEditBox.class.isAssignableFrom(kind))continue;
                    field.setAccessible(true);
                    if(focusedField(field.get(owner)))return true;
                }catch(ReflectiveOperationException|LinkageError|RuntimeException unreadable){
                    // unreadable field: not evidence of typing
                }
            }
        }
        return false;
    }
}
