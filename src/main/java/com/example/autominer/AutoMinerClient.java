package com.example.autominer;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

public class AutoMinerClient implements ClientModInitializer {

    private static final KeyBinding.Category CATEGORY =
        KeyBinding.Category.create(Identifier.of("autominer", "main"));
    private static KeyBinding menuKey;
    private static KeyBinding toggleKey;

    static boolean openMenuRequested = false;

    @Override
    public void onInitializeClient() {
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F12, CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.autominer.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (menuKey.wasPressed() || openMenuRequested) {
                openMenuRequested = false;
                if (client.player != null) client.setScreen(new MinerScreen());
            }
            while (toggleKey.wasPressed()) MinerLogic.toggle(client);
            MinerLogic.tick(client);
            RegionOutline.tick(client);
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("am")
                .then(ClientCommandManager.literal("menu").executes(ctx -> {
                    openMenuRequested = true;
                    return 1;
                }))
                .then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    MinerLogic.toggle(MinecraftClient.getInstance());
                    return 1;
                }))
                .then(ClientCommandManager.literal("pos1").executes(ctx -> {
                    MinerLogic.pos1 = lookPos();
                    msg("pos1 = " + MinerLogic.pos1.toShortString());
                    return 1;
                }))
                .then(ClientCommandManager.literal("pos2").executes(ctx -> {
                    MinerLogic.pos2 = lookPos();
                    msg("pos2 = " + MinerLogic.pos2.toShortString());
                    return 1;
                }))
                .then(ClientCommandManager.literal("clear").executes(ctx -> {
                    MinerLogic.pos1 = null;
                    MinerLogic.pos2 = null;
                    msg("Da xoa vung chon -> dung che do 3x3 truoc mat.");
                    return 1;
                }))
                .then(ClientCommandManager.literal("xp")
                    .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                        MinerLogic.useXpBottle = BoolArgumentType.getBool(ctx, "on");
                        msg("Nem binh XP sua cup: " + MinerLogic.useXpBottle);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("bot")
                    .then(ClientCommandManager.argument("on", BoolArgumentType.bool()).executes(ctx -> {
                        MinerLogic.botMove = BoolArgumentType.getBool(ctx, "on");
                        msg("Bot tu di: " + MinerLogic.botMove);
                        return 1;
                    })))
                .then(ClientCommandManager.literal("layer")
                    .then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 3)).executes(ctx -> {
                        MinerLogic.layerHeight = IntegerArgumentType.getInteger(ctx, "n");
                        msg("Dao moi dot " + MinerLogic.layerHeight + " tang.");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("normal")
                    .executes(ctx -> {
                        setNormalPick();
                        return 1;
                    })
                    .then(ClientCommandManager.literal("clear").executes(ctx -> {
                        MinerLogic.normalPickSlot = -1;
                        msg("Da xoa cai dat cup thuong (tu nhan dang cup 3x3 theo ten).");
                        return 1;
                    })))
                .then(ClientCommandManager.literal("status").executes(ctx -> {
                    msg("Bat: " + MinerLogic.enabled
                        + " | pos1: " + MinerLogic.pos1
                        + " | pos2: " + MinerLogic.pos2
                        + " | cup thuong: " + (MinerLogic.normalPickSlot >= 0 ? "o " + (MinerLogic.normalPickSlot + 1) : "tu nhan dang")
                        + " | binh XP: " + MinerLogic.useXpBottle
                        + " | bot: " + MinerLogic.botMove
                        + " | tang: " + MinerLogic.layerHeight);
                    return 1;
                }))
            ));
    }

    /** Dat cup thuong (khong phai 3x3) = o hotbar dang cam. Dung de doi cup khi roi xuong ho. */
    static void setNormalPick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        int slot = mc.player.getInventory().getSelectedSlot();
        if (!mc.player.getInventory().getStack(slot).isIn(ItemTags.PICKAXES)) {
            msg("Hay cam CUP THUONG (khong phai 3x3) tren tay roi bam lai.");
            return;
        }
        MinerLogic.normalPickSlot = slot;
        msg("Da dat cup thuong = o " + (slot + 1) + ". Khi roi xuong ho se doi sang cup nay.");
    }

    static BlockPos lookPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return bhr.getBlockPos();
        }
        return mc.player.getBlockPos();
    }

    static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[AutoMiner] " + s), false);
        }
    }
}
