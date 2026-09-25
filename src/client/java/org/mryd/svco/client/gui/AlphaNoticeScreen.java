package org.mryd.svco.client.gui;

import net.minecraft.ChatFormatting;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mryd.svco.client.SvcoConfig;

import java.util.List;

/**
 * One-time notice shown over the title screen on first launch: this mod is
 * alpha software — bugs, crashes and voice dropouts are expected. Dismissing
 * it (button or Esc) records the fact in the config so it never reappears.
 */
public class AlphaNoticeScreen extends Screen {

    private static final int PANEL_BG = 0xC0101014;
    private static final int PANEL_BORDER = 0xFF3A3A46;
    private static final int LINE_HEIGHT = 12;

    private static final List<String> BODY_KEYS = List.of(
            "svco.alpha.line1", "svco.alpha.line2", "svco.alpha.line3", "svco.alpha.line4");

    private final Screen parent;

    public AlphaNoticeScreen(Screen parent) {
        super(Component.translatable("svco.alpha.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("svco.alpha.accept"), button -> onClose())
                .bounds(width / 2 - 100, textTop() + (2 + BODY_KEYS.size()) * LINE_HEIGHT + 16, 200, 20)
                .build());
    }

    private int textTop() {
        int blockHeight = (2 + BODY_KEYS.size()) * LINE_HEIGHT + 16 + 20;
        return Math.max(30, (height - blockHeight) / 2);
    }

    @Override
    public void onClose() {
        SvcoConfig config = SvcoConfig.get();
        config.alphaNoticeShown = true;
        config.save();
        //? if >=26.2 {
        minecraft.gui.setScreen(parent);
        //?} else {
        /*minecraft.setScreen(parent);
        *///?}
    }

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        drawPanel(g);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawText(g);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        int top = textTop() - 12;
        int bottom = textTop() + (2 + BODY_KEYS.size()) * LINE_HEIGHT + 16 + 20 + 12;
        g.fill(width / 2 - 160, top, width / 2 + 160, bottom, PANEL_BG);
        g.outline(width / 2 - 160, top, 320, bottom - top, PANEL_BORDER);
    }

    private void drawText(GuiGraphicsExtractor g) {
        int y = textTop();
        g.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD),
                width / 2, y, 0xFFFFC107);
        y += 2 * LINE_HEIGHT;
        for (String key : BODY_KEYS) {
            g.centeredText(font, Component.translatable(key), width / 2, y, 0xFFE0E0E0);
            y += LINE_HEIGHT;
        }
    }
    //?} else {
    /*@Override
    //? if >=1.20.2 {
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        drawPanel(g);
    }
    //?} else {
    /^public void renderBackground(GuiGraphics g) {
        super.renderBackground(g);
        drawPanel(g);
    }
    ^///?}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        //? if <1.20.2
        /^renderBackground(g);^/
        super.render(g, mouseX, mouseY, partialTick);
        drawText(g);
    }

    private void drawPanel(GuiGraphics g) {
        int top = textTop() - 12;
        int bottom = textTop() + (2 + BODY_KEYS.size()) * LINE_HEIGHT + 16 + 20 + 12;
        g.fill(width / 2 - 160, top, width / 2 + 160, bottom, PANEL_BG);
        //? if >=1.21.9 <1.21.11 {
        /^g.submitOutline(width / 2 - 160, top, 320, bottom - top, PANEL_BORDER);
        ^///?} else {
        g.renderOutline(width / 2 - 160, top, 320, bottom - top, PANEL_BORDER);
        //?}
    }

    private void drawText(GuiGraphics g) {
        int y = textTop();
        g.drawCenteredString(font, title.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD),
                width / 2, y, 0xFFFFC107);
        y += 2 * LINE_HEIGHT;
        for (String key : BODY_KEYS) {
            g.drawCenteredString(font, Component.translatable(key), width / 2, y, 0xFFE0E0E0);
            y += LINE_HEIGHT;
        }
    }
    *///?}
}
