package dev.peace.mod.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** The PEACE logo as a clickable menu button that opens the desktop. */
public final class LogoButton extends AbstractWidget {
    private final Screen parent;

    public LogoButton(int x, int y, int w, Screen parent) {
        super(x, y, w, w * Theme.LOGO_H / Theme.LOGO_W, Component.literal("PEACE"));
        this.parent = parent;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        Theme.logo(g, getX(), getY(), getWidth(), getHeight());
    }

    @Override
    public void onClick(MouseButtonEvent e, boolean dbl) {
        Minecraft.getInstance().setScreenAndShow(new PeaceScreen(parent));
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput out) {}
}