package com.example.autominer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class MinerScreen extends Screen {

    public MinerScreen() {
        super(Text.literal("Auto Miner"));
    }

    private static Text onOff(String name, boolean v) {
        return Text.literal(name + ": " + (v ? "BẬT" : "TẮT"));
    }

    private void toggleButton(int x, int y, int w, String name, BooleanSupplier get, Consumer<Boolean> set) {
        addDrawableChild(ButtonWidget.builder(onOff(name, get.getAsBoolean()), b -> {
            set.accept(!get.getAsBoolean());
            b.setMessage(onOff(name, get.getAsBoolean()));
        }).dimensions(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        int cw = 150;
        int x1 = this.width / 2 - 155;
        int x2 = this.width / 2 + 5;
        int y = this.height / 2 - 96;
        int gap = 24;

        addDrawableChild(ButtonWidget.builder(onOff("Auto Mine", MinerLogic.enabled), b -> {
            MinerLogic.toggle(client);
            b.setMessage(onOff("Auto Mine", MinerLogic.enabled));
        }).dimensions(x1, y, 310, 20).build());
        y += gap;

        toggleButton(x1, y, cw, "Tự ăn", () -> MinerLogic.autoEat, v -> MinerLogic.autoEat = v);
        toggleButton(x2, y, cw, "Scaffold", () -> MinerLogic.autoScaffold, v -> MinerLogic.autoScaffold = v);
        y += gap;

        toggleButton(x1, y, cw, "An toàn lava", () -> MinerLogic.lavaSafety, v -> MinerLogic.lavaSafety = v);
        toggleButton(x2, y, cw, "Ném bình XP", () -> MinerLogic.useXpBottle, v -> MinerLogic.useXpBottle = v);
        y += gap;

        toggleButton(x1, y, cw, "Bot tự đi", () -> MinerLogic.botMove, v -> MinerLogic.botMove = v);
        addDrawableChild(ButtonWidget.builder(Text.literal("Đào mỗi đợt: " + MinerLogic.layerHeight + " tầng"), b -> {
            MinerLogic.layerHeight = MinerLogic.layerHeight >= 3 ? 1 : MinerLogic.layerHeight + 1;
            b.setMessage(Text.literal("Đào mỗi đợt: " + MinerLogic.layerHeight + " tầng"));
        }).dimensions(x2, y, cw, 20).build());
        y += gap;

        toggleButton(x1, y, cw, "Mua XP /shop", () -> MinerLogic.autoBuyXp, v -> MinerLogic.autoBuyXp = v);
        toggleButton(x2, y, cw, "Vứt đồ thừa", () -> MinerLogic.dropJunk, v -> MinerLogic.dropJunk = v);
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt Pos1"), b -> {
            MinerLogic.pos1 = AutoMinerClient.lookPos();
            AutoMinerClient.msg("pos1 = " + MinerLogic.pos1.toShortString());
        }).dimensions(x1, y, cw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt Pos2"), b -> {
            MinerLogic.pos2 = AutoMinerClient.lookPos();
            AutoMinerClient.msg("pos2 = " + MinerLogic.pos2.toShortString());
        }).dimensions(x2, y, cw, 20).build());
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Xóa vùng chọn (về hầm thẳng 3x3)"), b -> {
            MinerLogic.pos1 = null;
            MinerLogic.pos2 = null;
        }).dimensions(x1, y, 310, 20).build());
        y += gap;

        addDrawableChild(ButtonWidget.builder(Text.literal("Đặt cúp thường = ô đang cầm (dùng khi rơi hố)"), b ->
            AutoMinerClient.setNormalPick()).dimensions(x1, y, 310, 20).build());
        y += gap + 4;

        addDrawableChild(ButtonWidget.builder(Text.literal("Đóng"), b -> close())
            .dimensions(x1, y, 310, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 124, 0xFFFFFFFF);
        String mode = (MinerLogic.pos1 != null && MinerLogic.pos2 != null)
            ? "Chế độ: vùng chọn (đào từng tầng)" : "Chế độ: hầm thẳng 3x3";
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(mode), this.width / 2, this.height / 2 - 112, 0xFFAAAAAA);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
