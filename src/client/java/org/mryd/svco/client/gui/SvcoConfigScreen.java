package org.mryd.svco.client.gui;

import net.minecraft.ChatFormatting;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mryd.svco.client.FallbackManager;
import org.mryd.svco.client.SvcoConfig;
import org.mryd.svco.client.VoiceSession;
import org.mryd.svco.client.p2p.PeerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * Settings screen: a live status panel (connection state, peers, manual
 * reconnect) above a compact two-column grid of options. Everything applies
 * immediately; the file is written once on close.
 */
public class SvcoConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int HALF_WIDTH = 156;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int SECTION_GAP = 8; // between a row and the next section label row

    private static final int TITLE_Y = 8;
    private static final int PANEL_TOP = 18;
    private static final int PANEL_HEIGHT = 30;

    private static final int PANEL_BG = 0xC0101014;
    private static final int PANEL_BORDER = 0xFF3A3A46;
    private static final int SECTION_LINE = 0xFF33333D;
    private static final int LABEL_COLOR = 0xFFA0A0A8;
    private static final int VALUE_COLOR = 0xFFE0E0E0;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final Screen parent;

    private EditBox relayUrlBox;
    private Button reconnectButton;

    // Layout anchors, computed in init()
    private int left;
    private int sectionGeneralY;
    private int sectionNotificationsY;
    private int sectionRelayY;

    public SvcoConfigScreen(Screen parent) {
        super(Component.translatable("svco.gui.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        SvcoConfig config = SvcoConfig.get();
        left = (width - CONTENT_WIDTH) / 2;
        int right = left + CONTENT_WIDTH - HALF_WIDTH;

        // -- status panel: manual reconnect ------------------------------
        reconnectButton = addRenderableWidget(Button.builder(
                        Component.translatable("svco.gui.reconnect"),
                        button -> {
                            FallbackManager manager = FallbackManager.instance();
                            if (manager != null) {
                                manager.reconnectNow();
                            }
                        })
                .bounds(left + CONTENT_WIDTH - 94, PANEL_TOP + (PANEL_HEIGHT - ROW_HEIGHT) / 2, 88, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("svco.gui.reconnect.tooltip")))
                .build());

        // -- general ------------------------------------------------------
        sectionGeneralY = PANEL_TOP + PANEL_HEIGHT + 6;
        int y = sectionGeneralY + 10;
        addRenderableWidget(CycleButton.onOffBuilder(config.enabled)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.enabled.tooltip")))
                .create(left, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.enabled"),
                        (button, value) -> {
                            config.enabled = value;
                            FallbackManager manager = FallbackManager.instance();
                            if (manager != null) {
                                manager.onEnabledChanged(value);
                            }
                        }));
        addRenderableWidget(CycleButton.onOffBuilder(config.autoReconnect)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.auto_reconnect.tooltip")))
                .create(right, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.auto_reconnect"),
                        (button, value) -> config.autoReconnect = value));

        y += ROW_HEIGHT + ROW_GAP;
        addRenderableWidget(new SettingSlider(left, y, HALF_WIDTH, ROW_HEIGHT,
                1, SvcoConfig.MAX_VOICE_DISTANCE, 1, config.voiceDistance,
                v -> Component.translatable("svco.gui.distance", v.intValue()),
                v -> config.voiceDistance = v,
                Component.translatable("svco.gui.distance.tooltip")));
        addRenderableWidget(new SettingSlider(right, y, HALF_WIDTH, ROW_HEIGHT,
                0, 20, 1, config.maxReconnectAttempts,
                v -> v < 1
                        ? Component.translatable("svco.gui.max_attempts.infinite")
                        : Component.translatable("svco.gui.max_attempts", v.intValue()),
                v -> config.maxReconnectAttempts = (int) v,
                Component.translatable("svco.gui.max_attempts.tooltip")));

        y += ROW_HEIGHT + ROW_GAP;
        addRenderableWidget(new SettingSlider(left, y, HALF_WIDTH, ROW_HEIGHT,
                1, 30, 1, config.detectTimeoutTicks / 20.0,
                v -> Component.translatable("svco.gui.detect_timeout", v.intValue()),
                v -> config.detectTimeoutTicks = (int) (v * 20),
                Component.translatable("svco.gui.detect_timeout.tooltip")));
        addRenderableWidget(new SettingSlider(right, y, HALF_WIDTH, ROW_HEIGHT,
                1, 15, 1, config.punchTimeoutMs / 1000.0,
                v -> Component.translatable("svco.gui.punch_timeout", v.intValue()),
                v -> config.punchTimeoutMs = (long) (v * 1000),
                Component.translatable("svco.gui.punch_timeout.tooltip")));

        y += ROW_HEIGHT + ROW_GAP;
        addRenderableWidget(CycleButton.onOffBuilder(config.checkUpdates)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.check_updates.tooltip")))
                .create(left, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.check_updates"),
                        (button, value) -> config.checkUpdates = value));
        addRenderableWidget(CycleButton.onOffBuilder(config.autoUpdate)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.auto_update.tooltip")))
                .create(right, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.auto_update"),
                        (button, value) -> config.autoUpdate = value));

        // -- notifications -------------------------------------------------
        sectionNotificationsY = y + ROW_HEIGHT + SECTION_GAP;
        y = sectionNotificationsY + 10;
        addRenderableWidget(CycleButton.onOffBuilder(config.chatNotifications)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.chat_notifications.tooltip")))
                .create(left, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.chat_notifications"),
                        (button, value) -> config.chatNotifications = value));
        addRenderableWidget(CycleButton.onOffBuilder(config.toastNotifications)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.toast_notifications.tooltip")))
                .create(right, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.toast_notifications"),
                        (button, value) -> config.toastNotifications = value));

        // -- relay url -----------------------------------------------------
        sectionRelayY = y + ROW_HEIGHT + SECTION_GAP;
        y = sectionRelayY + 10;
        relayUrlBox = new EditBox(font, left, y, CONTENT_WIDTH, ROW_HEIGHT,
                Component.translatable("svco.gui.relay_url"));
        relayUrlBox.setMaxLength(256);
        relayUrlBox.setValue(SvcoConfig.RELAY_URL);
        relayUrlBox.setEditable(false); // relay address is fixed and cannot be changed
        relayUrlBox.setTooltip(Tooltip.create(Component.translatable("svco.gui.relay_url.tooltip")));
        addRenderableWidget(relayUrlBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(width / 2 - 100, height - 26, 200, ROW_HEIGHT)
                .build());
    }

    @Override
    public void tick() {
        FallbackManager manager = FallbackManager.instance();
        reconnectButton.active = manager != null && manager.canReconnectNow();
    }

    @Override
    public void onClose() {
        SvcoConfig.get().save();
        //? if >=26.2 {
        minecraft.gui.setScreen(parent);
        //?} else {
        /*minecraft.setScreen(parent);
        *///?}
    }

    // ---- rendering -------------------------------------------------------

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        // The panel sits under its widgets, so it belongs to the background pass
        drawPanel(g);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        drawForeground(g, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphicsExtractor g) {
        g.fill(left - 5, PANEL_TOP - 5, left + CONTENT_WIDTH + 5, PANEL_TOP + PANEL_HEIGHT + 5, PANEL_BG);
        g.outline(left - 5, PANEL_TOP - 5, CONTENT_WIDTH + 10, PANEL_HEIGHT + 10, PANEL_BORDER);
    }

    private void drawForeground(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.centeredText(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        FallbackManager manager = FallbackManager.instance();
        StatusView view = StatusView.of(manager);

        // status indicator: a pulsing dot while something is in flight
        int dotX = left + 4;
        int dotY = PANEL_TOP + (PANEL_HEIGHT - 6) / 2;
        g.fill(dotX, dotY, dotX + 6, dotY + 6, view.pulsing ? withPulse(view.color) : view.color);

        // clip long error/url strings so they never run under the button
        int textX = left + 16;
        g.enableScissor(textX, PANEL_TOP - 4, left + CONTENT_WIDTH - 98, PANEL_TOP + PANEL_HEIGHT + 4);
        g.text(font, view.headline, textX, PANEL_TOP + 3, VALUE_COLOR);
        g.text(font, view.detail.copy().withStyle(ChatFormatting.ITALIC), textX, PANEL_TOP + 16, LABEL_COLOR);
        g.disableScissor();

        sectionLabel(g, Component.translatable("svco.gui.section.general"), sectionGeneralY, null);
        sectionLabel(g, Component.translatable("svco.gui.section.notifications"), sectionNotificationsY, null);
        sectionLabel(g, Component.translatable("svco.gui.section.relay"), sectionRelayY, null);

        // hovering the status panel lists every peer and its voice path
        List<Component> peerLines = peerTooltip(view, mouseX, mouseY);
        if (peerLines != null) {
            g.setComponentTooltipForNextFrame(font, peerLines, mouseX, mouseY);
        }
    }

    /** "Label ───────────" with an optional right-aligned trailing note. */
    private void sectionLabel(GuiGraphicsExtractor g, Component label, int y, Component trailing) {
        g.text(font, label, left, y, LABEL_COLOR);
        int lineStart = left + font.width(label) + 6;
        int lineEnd = left + CONTENT_WIDTH;
        if (trailing != null) {
            int trailingWidth = font.width(trailing);
            g.text(font, trailing, left + CONTENT_WIDTH - trailingWidth, y, ERROR_COLOR);
            lineEnd = left + CONTENT_WIDTH - trailingWidth - 6;
        }
        if (lineEnd > lineStart) {
            g.horizontalLine(lineStart, lineEnd, y + 3, SECTION_LINE);
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
        drawForeground(g, mouseX, mouseY);
    }

    private void drawPanel(GuiGraphics g) {
        // The panel sits under its widgets, so it belongs to the background pass
        g.fill(left - 5, PANEL_TOP - 5, left + CONTENT_WIDTH + 5, PANEL_TOP + PANEL_HEIGHT + 5, PANEL_BG);
        //? if >=1.21.9 <1.21.11 {
        /^g.submitOutline(left - 5, PANEL_TOP - 5, CONTENT_WIDTH + 10, PANEL_HEIGHT + 10, PANEL_BORDER);
        ^///?} else {
        g.renderOutline(left - 5, PANEL_TOP - 5, CONTENT_WIDTH + 10, PANEL_HEIGHT + 10, PANEL_BORDER);
        //?}
    }

    private void drawForeground(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, width / 2, TITLE_Y, 0xFFFFFFFF);

        FallbackManager manager = FallbackManager.instance();
        StatusView view = StatusView.of(manager);

        // status indicator: a pulsing dot while something is in flight
        int dotX = left + 4;
        int dotY = PANEL_TOP + (PANEL_HEIGHT - 6) / 2;
        g.fill(dotX, dotY, dotX + 6, dotY + 6, view.pulsing ? withPulse(view.color) : view.color);

        // clip long error/url strings so they never run under the button
        int textX = left + 16;
        g.enableScissor(textX, PANEL_TOP - 4, left + CONTENT_WIDTH - 98, PANEL_TOP + PANEL_HEIGHT + 4);
        g.drawString(font, view.headline, textX, PANEL_TOP + 3, VALUE_COLOR);
        g.drawString(font, view.detail.copy().withStyle(ChatFormatting.ITALIC), textX, PANEL_TOP + 16, LABEL_COLOR);
        g.disableScissor();

        sectionLabel(g, Component.translatable("svco.gui.section.general"), sectionGeneralY, null);
        sectionLabel(g, Component.translatable("svco.gui.section.notifications"), sectionNotificationsY, null);
        sectionLabel(g, Component.translatable("svco.gui.section.relay"), sectionRelayY, null);

        // hovering the status panel lists every peer and its voice path
        List<Component> peerLines = peerTooltip(view, mouseX, mouseY);
        if (peerLines != null) {
            //? if >=1.21.6 {
            g.setComponentTooltipForNextFrame(font, peerLines, mouseX, mouseY);
            //?} else {
            /^g.renderComponentTooltip(font, peerLines, mouseX, mouseY);
            ^///?}
        }
    }

    /^* "Label ───────────" with an optional right-aligned trailing note. ^/
    private void sectionLabel(GuiGraphics g, Component label, int y, Component trailing) {
        g.drawString(font, label, left, y, LABEL_COLOR);
        int lineStart = left + font.width(label) + 6;
        int lineEnd = left + CONTENT_WIDTH;
        if (trailing != null) {
            int trailingWidth = font.width(trailing);
            g.drawString(font, trailing, left + CONTENT_WIDTH - trailingWidth, y, ERROR_COLOR);
            lineEnd = left + CONTENT_WIDTH - trailingWidth - 6;
        }
        if (lineEnd > lineStart) {
            g.hLine(lineStart, lineEnd, y + 3, SECTION_LINE);
        }
    }
    *///?}

    /** Lines for the peer tooltip, or null when it should not be shown. */
    private List<Component> peerTooltip(StatusView view, int mouseX, int mouseY) {
        if (view.peers == null || view.peers.isEmpty()
                || mouseX < left - 5 || mouseX > left + CONTENT_WIDTH - 96
                || mouseY < PANEL_TOP - 5 || mouseY > PANEL_TOP + PANEL_HEIGHT + 5) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("svco.gui.peers.header").withStyle(ChatFormatting.GRAY));
        for (PeerManager.PeerSnapshot peer : view.peers) {
            lines.add(Component.literal(peer.name() + " ").withStyle(ChatFormatting.WHITE)
                    .append(Component.translatable(peer.direct() ? "svco.gui.peers.direct" : "svco.gui.peers.relay")
                            .withStyle(peer.direct() ? ChatFormatting.GREEN : ChatFormatting.YELLOW)));
        }
        return lines;
    }

    private static int withPulse(int color) {
        double pulse = 0.55 + 0.45 * Math.sin(System.currentTimeMillis() / 250.0);
        int alpha = (int) (0xFF * pulse);
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** Everything the status panel shows, derived once per frame. */
    private record StatusView(Component headline, Component detail, int color, boolean pulsing,
                              List<PeerManager.PeerSnapshot> peers) {

        static StatusView of(FallbackManager manager) {
            String relayUrl = SvcoConfig.RELAY_URL;
            if (manager == null) {
                return new StatusView(Component.translatable("svco.status.idle"),
                        Component.literal(relayUrl), 0xFF9E9EA6, false, null);
            }
            return switch (manager.status()) {
                case IDLE -> new StatusView(Component.translatable("svco.status.idle"),
                        Component.literal(relayUrl), 0xFF9E9EA6, false, null);
                case DETECTING -> new StatusView(Component.translatable("svco.status.detecting"),
                        Component.literal(relayUrl), 0xFF64B5F6, true, null);
                case SERVER_VOICE -> new StatusView(Component.translatable("svco.status.server_voice"),
                        Component.translatable("svco.status.server_voice.detail"), 0xFF4DD0E1, false, null);
                case CONNECTING -> new StatusView(Component.translatable("svco.status.connecting"),
                        Component.literal(relayUrl), 0xFFFFC107, true, null);
                case CONNECTED -> connected(manager, relayUrl);
                case RECONNECTING -> new StatusView(
                        Component.translatable("svco.status.reconnecting", manager.retrySeconds(),
                                manager.reconnectAttempt()),
                        errorDetail(manager, relayUrl), 0xFFFFC107, true, null);
                case FAILED -> new StatusView(Component.translatable("svco.status.failed"),
                        errorDetail(manager, relayUrl), 0xFFF44336, false, null);
            };
        }

        private static StatusView connected(FallbackManager manager, String relayUrl) {
            VoiceSession session = manager.session();
            if (session == null) {
                return new StatusView(Component.translatable("svco.status.connected"),
                        Component.literal(relayUrl), 0xFF4CAF50, false, null);
            }
            int total = session.peerCount();
            long direct = session.directPeerCount();
            Component detail = total == 0
                    ? Component.translatable("svco.status.connected.alone")
                    : Component.translatable("svco.status.connected.peers", total, direct, total - direct);
            return new StatusView(Component.translatable("svco.status.connected"), detail,
                    0xFF4CAF50, false, session.snapshotPeers());
        }

        private static Component errorDetail(FallbackManager manager, String relayUrl) {
            String error = manager.lastError();
            return Component.literal(error == null || error.isBlank() ? relayUrl : error);
        }
    }

    /** A generic stepped slider rendering its value through a formatter. */
    private static class SettingSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final double step;
        private final Function<Double, Component> formatter;
        private final DoubleConsumer onApply;

        SettingSlider(int x, int y, int width, int height, double min, double max, double step,
                      double initial, Function<Double, Component> formatter, DoubleConsumer onApply,
                      Component tooltip) {
            super(x, y, width, height, formatter.apply(initial), (initial - min) / (max - min));
            this.min = min;
            this.max = max;
            this.step = step;
            this.formatter = formatter;
            this.onApply = onApply;
            setTooltip(Tooltip.create(tooltip));
            updateMessage();
        }

        private double steppedValue() {
            double raw = Math.round((min + value * (max - min)) / step) * step;
            return Math.max(min, Math.min(max, raw));
        }

        @Override
        protected void updateMessage() {
            setMessage(formatter.apply(steppedValue()));
        }

        @Override
        protected void applyValue() {
            onApply.accept(steppedValue());
        }
    }
}
