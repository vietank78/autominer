package com.example.autominer;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MinerLogic {
    // ===== Cai dat =====
    public static boolean enabled = false;
    public static boolean useFixCommand = false;
    public static boolean autoEat = true;
    public static boolean autoScaffold = true;
    public static boolean lavaSafety = true;
    public static boolean botMove = true;      // bot tu di chuyen
    public static int layerHeight = 3;         // so tang dao cung luc (1..3)
    public static BlockPos pos1 = null;
    public static BlockPos pos2 = null;

    private static final int FOOD_THRESHOLD = 14;
    private static final float DURABILITY_THRESHOLD = 30f;
    private static final double REACH_SQ = 4.3 * 4.3;
    private static final int STRAIGHT_LEN = 128;
    private static final long MAX_VOLUME = 5_000_000L;

    // ===== Vung dao =====
    private static int minX, maxX, minY, maxY, minZ, maxZ;
    private static boolean alongX, straight;
    private static int latMin, latMax, laneCount, groupH;
    private static int groupBottom;
    private static boolean descending;
    private static int curLane = -1;

    private static BlockPos currentTarget = null;
    private static int mineTicks = 0;
    private static final Set<BlockPos> skip = new HashSet<>();
    private static final List<BlockPos> rem = new ArrayList<>();
    private static int scanCd = 0;

    private static int pickSlot = -1, shovelSlot = -1;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static int stillTicks = 0;
    private static int hudTick = 0;

    private static boolean eating = false;
    private static int eatTicks = 0, eatCooldown = 0, fixCooldown = 0;

    private MinerLogic() {}

    // ===== Bat / tat =====
    public static void toggle(MinecraftClient mc) {
        if (mc.player == null) return;
        if (enabled) {
            disable(mc, "Da TAT.");
            return;
        }
        if (findBestTool(mc.player, ItemTags.PICKAXES) < 0) {
            AutoMinerClient.msg("Khong tim thay cuc trong hotbar!");
            return;
        }
        if (!setupRegion(mc.player)) return;
        pickSlot = -1;
        shovelSlot = -1;
        currentTarget = null;
        mineTicks = 0;
        curLane = -1;
        skip.clear();
        rem.clear();
        scanCd = 0;
        stillTicks = 0;
        eating = false;
        lastPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        enabled = true;
        AutoMinerClient.msg("Da BAT: " + (straight ? "ham thang 3x3 (dai " + STRAIGHT_LEN + ")"
            : "vung chon, moi dot " + groupH + " tang") + (botMove ? ", bot tu di." : "."));
    }

    private static boolean setupRegion(ClientPlayerEntity p) {
        BlockPos fp = p.getBlockPos();
        if (pos1 != null && pos2 != null) {
            straight = false;
            groupH = Math.max(1, Math.min(3, layerHeight));
            minX = Math.min(pos1.getX(), pos2.getX());
            maxX = Math.max(pos1.getX(), pos2.getX());
            minY = Math.min(pos1.getY(), pos2.getY());
            maxY = Math.max(pos1.getY(), pos2.getY());
            minZ = Math.min(pos1.getZ(), pos2.getZ());
            maxZ = Math.max(pos1.getZ(), pos2.getZ());
            long vol = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
            if (vol > MAX_VOLUME) {
                AutoMinerClient.msg("Vung qua lon (" + vol + " block). Toi da " + MAX_VOLUME + ".");
                return false;
            }
            if (fp.getX() < minX || fp.getX() > maxX || fp.getZ() < minZ || fp.getZ() > maxZ) {
                AutoMinerClient.msg("Hay dung TRONG vung chon (hoac ngay phia tren no).");
                return false;
            }
            if (fp.getY() < minY) {
                AutoMinerClient.msg("Ban dang o duoi vung chon.");
                return false;
            }
            alongX = (maxX - minX) >= (maxZ - minZ);
            int ideal = Math.max(minY, maxY - groupH + 1);
            groupBottom = Math.min(fp.getY(), ideal);
            descending = fp.getY() > groupBottom;
        } else {
            straight = true;
            groupH = 3;
            Direction f = p.getHorizontalFacing();
            alongX = f.getAxis() == Direction.Axis.X;
            boolean pos = f.getDirection() == Direction.AxisDirection.POSITIVE;
            if (alongX) {
                minX = pos ? fp.getX() + 1 : fp.getX() - STRAIGHT_LEN;
                maxX = pos ? fp.getX() + STRAIGHT_LEN : fp.getX() - 1;
                minZ = fp.getZ() - 1;
                maxZ = fp.getZ() + 1;
            } else {
                minZ = pos ? fp.getZ() + 1 : fp.getZ() - STRAIGHT_LEN;
                maxZ = pos ? fp.getZ() + STRAIGHT_LEN : fp.getZ() - 1;
                minX = fp.getX() - 1;
                maxX = fp.getX() + 1;
            }
            minY = fp.getY();
            maxY = fp.getY() + 2;
            groupBottom = fp.getY();
            descending = false;
        }
        latMin = alongX ? minZ : minX;
        latMax = alongX ? maxZ : maxX;
        laneCount = (int) Math.ceil((latMax - latMin + 1) / 3.0);
        return true;
    }

    private static void disable(MinecraftClient mc, String reason) {
        enabled = false;
        if (eating) stopEating(mc);
        releaseMove(mc);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        AutoMinerClient.msg(reason);
    }

    /** Hop bao quanh nhom tang dang dao (de ve vien xanh). null neu khong chay. */
    public static BlockPos[] activeBox() {
        if (!enabled) return null;
        return new BlockPos[]{new BlockPos(minX, groupBottom, minZ), new BlockPos(maxX, groupTop(), maxZ)};
    }

    private static int groupTop() {
        return Math.min(groupBottom + groupH - 1, maxY);
    }

    // ===== Vong lap chinh =====
    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        ClientPlayerInteractionManager im = mc.interactionManager;
        if (!enabled || p == null || w == null || im == null) return;

        if (fixCooldown > 0) fixCooldown--;
        if (eatCooldown > 0) eatCooldown--;

        // 1. An
        if (!autoEat && eating) stopEating(mc);
        if (autoEat && handleEating(mc, p, im)) return;

        // 2. Cong cu + do ben
        PlayerInventory inv = p.getInventory();
        pickSlot = resolveTool(p, ItemTags.PICKAXES, pickSlot);
        if (pickSlot < 0) {
            disable(mc, "Het cuc! Da dung.");
            return;
        }
        ItemStack pick = inv.getStack(pickSlot);
        if (durabilityPercent(pick) < DURABILITY_THRESHOLD) {
            if (useFixCommand && fixCooldown == 0 && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendChatCommand("fix");
                fixCooldown = 20 * 30;
            }
            if (pick.isDamageable() && pick.getMaxDamage() - pick.getDamage() <= 2) {
                disable(mc, "Cuc sap hong! Da dung.");
                return;
            }
        }
        shovelSlot = resolveTool(p, ItemTags.SHOVELS, shovelSlot);
        if (shovelSlot >= 0) {
            ItemStack sh = inv.getStack(shovelSlot);
            if (sh.isDamageable() && sh.getMaxDamage() - sh.getDamage() <= 2) shovelSlot = -1;
        }

        // 3. Scaffold (chi khi dang dao ngang)
        BlockPos below = p.getBlockPos().down();
        if (autoScaffold && !descending && !p.getAbilities().flying && w.getBlockState(below).isAir()) {
            im.cancelBlockBreaking();
            if (placeAt(p, w, im, below)) return;
        }

        hud(p);

        if (descending) doDescend(mc, p, w, im);
        else doWork(mc, p, w, im);
    }

    // ===== Dao 1 tang =====
    private static void doWork(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w, ClientPlayerInteractionManager im) {
        // tiep tuc block dang dao
        if (currentTarget != null) {
            if (mineable(w, currentTarget)
                && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(currentTarget)) <= REACH_SQ) {
                releaseMove(mc);
                mineBlock(mc, p, w, im, currentTarget);
                return;
            }
            currentTarget = null;
            mineTicks = 0;
        }

        // cap nhat danh sach con lai
        if (scanCd-- <= 0) {
            rescan(w);
        } else {
            rem.removeIf(b -> !mineable(w, b));
            if (rem.isEmpty()) rescan(w);
        }

        // het block cua tang -> xuong tang
        if (rem.isEmpty()) {
            releaseMove(mc);
            im.cancelBlockBreaking();
            if (groupBottom <= minY) {
                disable(mc, "Hoan thanh!");
                return;
            }
            if (!botMove) {
                disable(mc, "Xong tang nay. Bat Bot de tu dao xuong.");
                return;
            }
            groupBottom = Math.max(minY, groupBottom - groupH);
            descending = true;
            curLane = -1;
            currentTarget = null;
            stillTicks = 0;
            return;
        }

        // chon hang (lane)
        int[] cnt = new int[laneCount];
        for (BlockPos b : rem) cnt[laneOf(b)]++;
        double pLat = alongX ? p.getZ() : p.getX();
        if (curLane < 0 || curLane >= laneCount || cnt[curLane] == 0) {
            int best = -1;
            double bd = 1e18;
            for (int i = 0; i < laneCount; i++) {
                if (cnt[i] == 0) continue;
                double d = Math.abs(laneCenter(i) - pLat);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            curLane = best;
        }

        // block trong tam voi cua hang hien tai: tang tren truoc, gan truoc
        Vec3d eye = p.getEyePos();
        BlockPos cand = null;
        double cd = 0;
        for (BlockPos b : rem) {
            if (laneOf(b) != curLane) continue;
            double d = eye.squaredDistanceTo(Vec3d.ofCenter(b));
            if (d > REACH_SQ) continue;
            if (cand == null || b.getY() > cand.getY() || (b.getY() == cand.getY() && d < cd)) {
                cand = b;
                cd = d;
            }
        }
        if (cand != null) {
            stillTicks = 0;
            releaseMove(mc);
            currentTarget = cand;
            mineTicks = 0;
            mineBlock(mc, p, w, im, cand);
            return;
        }

        // khong co gi trong tam -> di chuyen
        im.cancelBlockBreaking();
        if (!botMove) return;

        double pAlong = alongX ? p.getX() : p.getZ();
        BlockPos near = null;
        double nd = 1e18;
        for (BlockPos b : rem) {
            if (laneOf(b) != curLane) continue;
            double d = Math.abs((alongX ? b.getX() : b.getZ()) + 0.5 - pAlong);
            if (d < nd) {
                nd = d;
                near = b;
            }
        }
        if (near == null) return;
        double lc = laneCenter(curLane);
        double tx = alongX ? near.getX() + 0.5 : lc;
        double tz = alongX ? lc : near.getZ() + 0.5;
        walkTo(mc, p, tx, tz);

        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        if (pos.squaredDistanceTo(lastPos) < 0.0004) stillTicks++;
        else stillTicks = 0;
        lastPos = pos;
        if (stillTicks == 100) {
            skip.add(near);
            rem.remove(near);
        }
        if (stillTicks > 200) disable(mc, "Bi ket, da dung.");
    }

    // ===== Dao xuong tang duoi (dao 1 cot ngay duoi chan) =====
    private static void doDescend(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w, ClientPlayerInteractionManager im) {
        releaseMove(mc);
        BlockPos feet = p.getBlockPos();
        if (feet.getY() <= groupBottom) {
            descending = false;
            curLane = -1;
            currentTarget = null;
            rem.clear();
            scanCd = 0;
            stillTicks = 0;
            im.cancelBlockBreaking();
            return;
        }
        if (!p.isOnGround()) return;
        BlockPos below = feet.down();
        BlockState s = w.getBlockState(below);
        if (s.isAir() || s.getBlock() instanceof FluidBlock) {
            if (++stillTicks > 100) disable(mc, "Khong xuong duoc, da dung.");
            return;
        }
        stillTicks = 0;
        if (s.getHardness(w, below) < 0 || skip.contains(below)) {
            disable(mc, "Khong dao xuong duoc (bedrock/khong dao duoc).");
            return;
        }
        if (currentTarget == null || !currentTarget.equals(below)) {
            currentTarget = below;
            mineTicks = 0;
        }
        mineBlock(mc, p, w, im, below);
    }

    // ===== Dao 1 block =====
    private static void mineBlock(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w,
                                  ClientPlayerInteractionManager im, BlockPos target) {
        BlockState s = w.getBlockState(target);

        if (lavaSafety) {
            BlockPos lava = findLavaNear(w, target);
            if (lava != null) {
                im.cancelBlockBreaking();
                currentTarget = null;
                if (!placeAt(p, w, im, lava)) {
                    disable(mc, "Gap lava nhung khong co block/cho dat! Da dung.");
                }
                return;
            }
        }

        // Cat / soi / dat -> xeng, con lai -> cuc
        int slot = (s.isIn(BlockTags.SHOVEL_MINEABLE) && shovelSlot >= 0) ? shovelSlot : pickSlot;
        PlayerInventory inv = p.getInventory();
        if (inv.getSelectedSlot() != slot) inv.setSelectedSlot(slot);

        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(target);
        aim(p, c);
        Direction face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        im.updateBlockBreakingProgress(target, face);
        p.swingHand(Hand.MAIN_HAND);

        if (++mineTicks > 400) {
            skip.add(target);
            rem.remove(target);
            currentTarget = null;
            mineTicks = 0;
            im.cancelBlockBreaking();
            AutoMinerClient.msg("Bo qua 1 block khong dao duoc.");
        }
    }

    // ===== Tien ich vung / hang =====
    private static boolean mineable(ClientWorld w, BlockPos pos) {
        if (skip.contains(pos)) return false;
        BlockState s = w.getBlockState(pos);
        if (s.isAir() || s.getBlock() instanceof FluidBlock) return false;
        return s.getHardness(w, pos) >= 0;
    }

    private static void rescan(ClientWorld w) {
        rem.clear();
        int top = groupTop();
        for (int y = top; y >= groupBottom; y--)
            for (int x = minX; x <= maxX; x++)
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (mineable(w, pos)) rem.add(pos);
                }
        scanCd = 20;
    }

    private static int laneOf(BlockPos b) {
        int lat = alongX ? b.getZ() : b.getX();
        return Math.min(laneCount - 1, (lat - latMin) / 3);
    }

    private static double laneCenter(int i) {
        int width = Math.min(3, (latMax - latMin + 1) - 3 * i);
        return latMin + 3 * i + (width - 1) / 2.0 + 0.5;
    }

    private static void hud(ClientPlayerEntity p) {
        if (hudTick++ % 20 != 0) return;
        int total = (int) Math.ceil((maxY - minY + 1) / (double) groupH);
        int idx = (maxY - groupTop()) / groupH + 1;
        p.sendMessage(Text.literal("Tang " + idx + "/" + total
            + " | Hang " + (curLane + 1) + "/" + laneCount
            + " | Con " + rem.size() + " block"
            + (descending ? " | dang xuong tang" : "")), true);
    }

    // ===== Di chuyen =====
    private static void walkTo(MinecraftClient mc, ClientPlayerEntity p, double tx, double tz) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        if (dx * dx + dz * dz > 0.01) {
            p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        }
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(p.horizontalCollision && p.isOnGround());
    }

    private static void releaseMove(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    // ===== Lava =====
    private static BlockPos findLavaNear(ClientWorld w, BlockPos pos) {
        if (w.getFluidState(pos).isIn(FluidTags.LAVA)) return pos;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (w.getFluidState(n).isIn(FluidTags.LAVA)) return n;
        }
        return null;
    }

    // ===== Dat block (lava + scaffold) =====
    private static boolean placeAt(ClientPlayerEntity p, ClientWorld w,
                                   ClientPlayerInteractionManager im, BlockPos target) {
        int slot = findBlockSlot(p, w);
        if (slot < 0) return false;
        Vec3d eye = p.getEyePos();
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState s = w.getBlockState(support);
            if (s.isAir() || !w.getFluidState(support).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(support).add(
                face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.squaredDistanceTo(hitVec) > REACH_SQ) continue;

            p.getInventory().setSelectedSlot(slot);
            aim(p, hitVec);
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            ActionResult r = im.interactBlock(p, Hand.MAIN_HAND, hit);
            if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private static int findBlockSlot(ClientPlayerEntity p, ClientWorld w) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(Items.COBBLESTONE) || st.isOf(Items.COBBLED_DEEPSLATE)
                || st.isOf(Items.NETHERRACK) || st.isOf(Items.DIRT)) return i;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack st = inv.getStack(i);
            if (st.getItem() instanceof BlockItem bi) {
                Block b = bi.getBlock();
                if (b instanceof FallingBlock || b == Blocks.TNT) continue;
                if (b.getDefaultState().hasBlockEntity()) continue;
                if (b.getDefaultState().isFullCube(w, BlockPos.ORIGIN)) return i;
            }
        }
        return -1;
    }

    // ===== Cong cu / do ben =====
    private static float durabilityPercent(ItemStack s) {
        if (!s.isDamageable()) return 100f;
        int max = s.getMaxDamage();
        return (max - s.getDamage()) * 100f / max;
    }

    private static int findBestTool(ClientPlayerEntity p, TagKey<Item> tag) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        float bp = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isIn(tag)) continue;
            float pp = durabilityPercent(s);
            if (pp > bp) {
                bp = pp;
                best = i;
            }
        }
        return best;
    }

    private static int resolveTool(ClientPlayerEntity p, TagKey<Item> tag, int cur) {
        PlayerInventory inv = p.getInventory();
        if (cur >= 0) {
            ItemStack s = inv.getStack(cur);
            if (s.isIn(tag) && durabilityPercent(s) >= DURABILITY_THRESHOLD) return cur;
        }
        return findBestTool(p, tag);
    }

    // ===== Tu dong an =====
    private static boolean isGoodFood(ItemStack s) {
        if (!s.contains(DataComponentTypes.FOOD)) return false;
        return !(s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE)
            || s.isOf(Items.POISONOUS_POTATO) || s.isOf(Items.PUFFERFISH)
            || s.isOf(Items.CHORUS_FRUIT));
    }

    private static boolean handleEating(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        int food = p.getHungerManager().getFoodLevel();
        if (eating) {
            eatTicks++;
            boolean done = !p.isUsingItem() && eatTicks > 3;
            if (done || eatTicks > 100) {
                stopEating(mc);
                eatCooldown = 5;
                return false;
            }
            mc.options.useKey.setPressed(true);
            return true;
        }
        if (food <= FOOD_THRESHOLD && eatCooldown == 0) {
            PlayerInventory inv = p.getInventory();
            for (int i = 0; i < 9; i++) {
                if (isGoodFood(inv.getStack(i))) {
                    releaseMove(mc);
                    im.cancelBlockBreaking();
                    inv.setSelectedSlot(i);
                    eating = true;
                    eatTicks = 0;
                    mc.options.useKey.setPressed(true);
                    return true;
                }
            }
        }
        return false;
    }

    private static void stopEating(MinecraftClient mc) {
        eating = false;
        mc.options.useKey.setPressed(false);
        if (mc.player != null && pickSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(pickSlot);
        }
    }

    // ===== Xoay dau =====
    private static void aim(ClientPlayerEntity p, Vec3d v) {
        Vec3d e = p.getEyePos();
        double dx = v.x - e.x, dy = v.y - e.y, dz = v.z - e.z;
        double hd = Math.sqrt(dx * dx + dz * dz);
        if (hd > 0.6) {
            p.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
        }
        p.setPitch((float) -Math.toDegrees(Math.atan2(dy, hd)));
    }
}
