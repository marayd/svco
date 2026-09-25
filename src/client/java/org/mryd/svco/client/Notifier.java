package org.mryd.svco.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Routes user-facing status messages to chat and/or toasts, per config. */
final class Notifier {

    //? if >=1.20.2 {
    private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(5000L);
    //?} else {
    /*private static final SystemToast.SystemToastIds TOAST_ID = SystemToast.SystemToastIds.PERIODIC_NOTIFICATION;
    *///?}

    private Notifier() {
    }

    static void success(String key, Object... args) {
        show(Component.translatable(key, args).withStyle(ChatFormatting.GREEN));
    }

    static void info(String key, Object... args) {
        show(Component.translatable(key, args).withStyle(ChatFormatting.YELLOW));
    }

    static void error(String key, Object... args) {
        show(Component.translatable(key, args).withStyle(ChatFormatting.RED));
    }

    private static void show(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        SvcoConfig config = SvcoConfig.get();
        if (config.chatNotifications && minecraft.player != null) {
            //? if >=26.1 {
            minecraft.player.sendSystemMessage(
                    Component.literal("[SVCO] ").withStyle(ChatFormatting.DARK_GRAY).append(message));
            //?} else {
            /*minecraft.player.displayClientMessage(
                    Component.literal("[SVCO] ").withStyle(ChatFormatting.DARK_GRAY).append(message), false);
            *///?}
        }
        if (config.toastNotifications) {
            //? if >=26.2 {
            SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST_ID,
                    Component.translatable("svco.toast.title"), message);
            //?} else if >=1.21.2 {
            /*SystemToast.addOrUpdate(minecraft.getToastManager(), TOAST_ID,
                    Component.translatable("svco.toast.title"), message);
            *///?} else {
            /*SystemToast.addOrUpdate(minecraft.getToasts(), TOAST_ID,
                    Component.translatable("svco.toast.title"), message);
            *///?}
        }
    }
}
