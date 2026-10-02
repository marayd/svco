package org.mryd.svco.client.gui;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.mryd.svco.client.FallbackManager;
import org.mryd.svco.client.SvcoConfig;
import org.mryd.svco.client.proxy.ProxyCheck;
import org.mryd.svco.client.proxy.ProxySettings;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * SOCKS5 proxy settings, opened from the main screen. Edits stay local until
 * "Done"; Esc and "Cancel" discard them. Saving changed settings rebuilds a
 * live relay session so it never keeps running on the old route.
 */
public class ProxyConfigScreen extends Screen {

    private static final int CONTENT_WIDTH = 320;
    private static final int HALF_WIDTH = 156;
    private static final int ROW_HEIGHT = 20;
    private static final int PORT_WIDTH = 72;

    private static final int LABEL_COLOR = 0xFFA0A0A8;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int OK_COLOR = 0xFF66BB6A;
    private static final int ERROR_COLOR = 0xFFFF5555;

    private final Screen parent;

    private boolean enabled;
    private EditBox hostBox;
    private EditBox portBox;
    private EditBox userBox;
    private EditBox passwordBox;
    private Button testButton;

    private int left;
    private int introY;
    private int hostLabelY;
    private int userLabelY;
    private int resultY;

    /** Latest test or validation outcome; written from the test thread. */
    private volatile Component result;
    private volatile int resultColor = LABEL_COLOR;
    private volatile boolean testing;

    public ProxyConfigScreen(Screen parent) {
        super(Component.translatable("svco.gui.proxy.title"));
        this.parent = parent;
        this.enabled = SvcoConfig.get().proxyEnabled;
    }

    @Override
    protected void init() {
        SvcoConfig config = SvcoConfig.get();
        left = (width - CONTENT_WIDTH) / 2;
        int right = left + CONTENT_WIDTH - HALF_WIDTH;

        // Rebuilt on resize: carry over what the player already typed
        String host = hostBox != null ? hostBox.getValue() : config.proxyHost;
        String port = portBox != null ? portBox.getValue() : String.valueOf(config.proxyPort);
        String user = userBox != null ? userBox.getValue() : config.proxyUsername;
        String password = passwordBox != null ? passwordBox.getValue() : config.proxyPassword;

        introY = 24;
        int y = introY + 2 * 10 + 8;
        addRenderableWidget(CycleButton.onOffBuilder(enabled)
                .withTooltip(v -> Tooltip.create(Component.translatable("svco.gui.proxy.enabled.tooltip")))
                .create(left, y, HALF_WIDTH, ROW_HEIGHT, Component.translatable("svco.gui.proxy.enabled"),
                        (button, value) -> enabled = value));
        testButton = addRenderableWidget(Button.builder(Component.translatable("svco.gui.proxy.test"),
                        button -> runTest())
                .bounds(right, y, HALF_WIDTH, ROW_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("svco.gui.proxy.test.tooltip")))
                .build());

        hostLabelY = y + ROW_HEIGHT + 8;
        y = hostLabelY + 10;
        hostBox = box(left, y, CONTENT_WIDTH - PORT_WIDTH - 4, "svco.gui.proxy.host", host, 253);
        portBox = box(left + CONTENT_WIDTH - PORT_WIDTH, y, PORT_WIDTH, "svco.gui.proxy.port", port, 5);
        portBox.setResponder(value -> portBox.setTextColor(parsePort(value) > 0 ? TEXT_COLOR : ERROR_COLOR));
        portBox.setTextColor(parsePort(port) > 0 ? TEXT_COLOR : ERROR_COLOR);

        userLabelY = y + ROW_HEIGHT + 8;
        y = userLabelY + 10;
        userBox = box(left, y, HALF_WIDTH, "svco.gui.proxy.username", user, 255);
        passwordBox = box(right, y, HALF_WIDTH, "svco.gui.proxy.password", password, 255);
        // Shown as asterisks: the screen may be streamed or screenshotted
        //? if >=1.21.9 {
        passwordBox.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        //?} else {
        /*passwordBox.setFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        *///?}

        resultY = y + ROW_HEIGHT + 8;

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(width / 2 - 154, height - 26, 150, ROW_HEIGHT)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> saveAndClose())
                .bounds(width / 2 + 4, height - 26, 150, ROW_HEIGHT)
                .build());
    }

    private EditBox box(int x, int y, int boxWidth, String key, String value, int maxLength) {
        EditBox box = new EditBox(font, x, y, boxWidth, ROW_HEIGHT, Component.translatable(key));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setTooltip(Tooltip.create(Component.translatable(key + ".tooltip")));
        return addRenderableWidget(box);
    }

    @Override
    public void tick() {
        testButton.active = !testing;
    }

    // ---- actions ---------------------------------------------------------

    /** The settings as currently typed, in a throwaway config. */
    private SvcoConfig draft() {
        SvcoConfig draft = new SvcoConfig();
        draft.proxyEnabled = true;
        draft.proxyHost = hostBox.getValue().trim();
        draft.proxyPort = parsePort(portBox.getValue());
        draft.proxyUsername = userBox.getValue();
        draft.proxyPassword = passwordBox.getValue();
        return draft;
    }

    private void runTest() {
        ProxySettings proxy;
        try {
            proxy = ProxySettings.fromConfig(draft());
        } catch (IOException e) {
            showResult(Component.literal(e.getMessage()), ERROR_COLOR);
            return;
        }
        testing = true;
        showResult(Component.translatable("svco.gui.proxy.testing", proxy.toString()), LABEL_COLOR);
        ProxyCheck.run(proxy, SvcoConfig.RELAY_URL).whenComplete((check, error) -> {
            testing = false;
            if (error != null) {
                String message = error.getMessage() == null ? error.toString() : error.getMessage();
                showResult(Component.translatable("svco.gui.proxy.test.failed", message), ERROR_COLOR);
            } else {
                showResult(Component.translatable("svco.gui.proxy.test.ok", check.tcpMillis(), check.udpRelay()),
                        OK_COLOR);
            }
        });
    }

    private void showResult(Component message, int color) {
        resultColor = color;
        result = message;
    }

    private void saveAndClose() {
        SvcoConfig draft = draft();
        draft.proxyEnabled = enabled;
        if (enabled) {
            try {
                ProxySettings.fromConfig(draft);
            } catch (IOException e) {
                // Saving would only make every connect fail; say why right here
                showResult(Component.literal(e.getMessage()), ERROR_COLOR);
                return;
            }
        }

        SvcoConfig config = SvcoConfig.get();
        boolean changed = config.proxyEnabled != draft.proxyEnabled
                || !Objects.equals(config.proxyHost, draft.proxyHost)
                || (draft.proxyPort > 0 && config.proxyPort != draft.proxyPort)
                || !Objects.equals(config.proxyUsername, draft.proxyUsername)
                || !Objects.equals(config.proxyPassword, draft.proxyPassword);
        config.proxyEnabled = draft.proxyEnabled;
        config.proxyHost = draft.proxyHost;
        if (draft.proxyPort > 0) {
            config.proxyPort = draft.proxyPort;
        }
        config.proxyUsername = draft.proxyUsername;
        config.proxyPassword = draft.proxyPassword;
        config.save();

        if (changed) {
            FallbackManager manager = FallbackManager.instance();
            if (manager != null) {
                manager.onProxyChanged();
            }
        }
        onClose();
    }

    /** 1..65535, or 0 when not a valid port. */
    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= 1 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void onClose() {
        //? if >=26.2 {
        minecraft.gui.setScreen(parent);
        //?} else {
        /*minecraft.setScreen(parent);
        *///?}
    }

    // ---- rendering -------------------------------------------------------

    //? if >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
        int y = introY;
        for (FormattedCharSequence line : font.split(Component.translatable("svco.gui.proxy.intro"), CONTENT_WIDTH)) {
            g.text(font, line, left, y, LABEL_COLOR);
            y += 10;
        }
        g.text(font, Component.translatable("svco.gui.proxy.host"), left, hostLabelY, LABEL_COLOR);
        g.text(font, Component.translatable("svco.gui.proxy.port"), left + CONTENT_WIDTH - PORT_WIDTH, hostLabelY, LABEL_COLOR);
        g.text(font, Component.translatable("svco.gui.proxy.username"), left, userLabelY, LABEL_COLOR);
        g.text(font, Component.translatable("svco.gui.proxy.password"), left + CONTENT_WIDTH - HALF_WIDTH, userLabelY, LABEL_COLOR);
        Component message = result;
        if (message != null) {
            y = resultY;
            for (FormattedCharSequence line : font.split(message, CONTENT_WIDTH)) {
                g.text(font, line, left, y, resultColor);
                y += 10;
            }
        }
    }
    //?} else {
    /*@Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        //? if <1.20.2
        /^renderBackground(g);^/
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        int y = introY;
        for (FormattedCharSequence line : font.split(Component.translatable("svco.gui.proxy.intro"), CONTENT_WIDTH)) {
            g.drawString(font, line, left, y, LABEL_COLOR);
            y += 10;
        }
        g.drawString(font, Component.translatable("svco.gui.proxy.host"), left, hostLabelY, LABEL_COLOR);
        g.drawString(font, Component.translatable("svco.gui.proxy.port"), left + CONTENT_WIDTH - PORT_WIDTH, hostLabelY, LABEL_COLOR);
        g.drawString(font, Component.translatable("svco.gui.proxy.username"), left, userLabelY, LABEL_COLOR);
        g.drawString(font, Component.translatable("svco.gui.proxy.password"), left + CONTENT_WIDTH - HALF_WIDTH, userLabelY, LABEL_COLOR);
        Component message = result;
        if (message != null) {
            y = resultY;
            for (FormattedCharSequence line : font.split(message, CONTENT_WIDTH)) {
                g.drawString(font, line, left, y, resultColor);
                y += 10;
            }
        }
    }
    *///?}
}
