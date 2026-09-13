package dev.peace.mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import dev.peace.mod.ui.PeaceScreen;
import dev.peace.mod.ui.LogoButton;
import dev.peace.mod.ui.Theme;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

public final class PeaceMod implements ClientModInitializer {
    private boolean shiftWasDown = false;

    /** True while the player is typing (chat/command/any text field); right-shift must not hijack. */
    private static boolean typing(Screen screen) {
        if (screen instanceof ChatScreen) return true;          // chat + command (subclass)
        if (screen != null && screen.getFocused() instanceof EditBox) return true; // rename/search/sign fields
        return false;
    }

    @Override
    public void onInitializeClient() {
        Theme.load();
        Net.init();
        Discord.init(); // ping-home listener; auto-registers servers who advertise on Discord

        // Fire the vanish op right after joining when "Join Vanished" was requested.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (Net.consumeVanishOnJoin()) client.execute(() -> Net.send(Net.op("vanish"), j -> {}));
        });

        // Open the desktop with RIGHT SHIFT (in-game) - never while a text field is focused.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
            if (down && !shiftWasDown && !PeaceScreen.OPEN && client.player != null && !typing(client.gui.screen()))
                client.setScreenAndShow(new PeaceScreen());
            shiftWasDown = down;
        });

        // PEACE logo button on title/pause menus, and RIGHT SHIFT opens the desktop from any menu.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PeaceScreen) return;
            if (screen instanceof TitleScreen || screen instanceof PauseScreen)
                Screens.getWidgets(screen).add(new LogoButton(6, 6, Theme.LOGO_DISPLAY_W, screen));
            ScreenKeyboardEvents.afterKeyPress(screen).register((scr, keyEvent) -> {
                if (keyEvent.key() == GLFW.GLFW_KEY_RIGHT_SHIFT && !PeaceScreen.OPEN && !typing(scr))
                    client.setScreenAndShow(new PeaceScreen(scr));
            });
        });
    }
}
