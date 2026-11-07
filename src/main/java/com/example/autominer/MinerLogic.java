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
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
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
    public static boolean useXpBottle = true;     // tu nem binh XP de Mending sua cup
    public static boolean autoEat = true;
    public static boolean autoScaffold = true;
    public static boolean lavaSafety = true;
    public static boolean botMove = true;      // bot tu di chuyen
    public static int layerHeight = 3;         // so tang dao cung luc (1..3)
    public static int normalPickSlot = -1;     // o hotbar (0..8) cua cup thuong; -1 = tu nhan dang cup 3x3 theo ten/lore
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
    private static int laneDir = 1;   // huong quet doc theo hang: +1 hoac -1

    // ===== Thoat ho (roi xuong thap hon tang dang dao) =====
    private static boolean escaping = false;
    private static Direction escDir = Direction.EAST;
    private static int escTries = 0, escSlot = -1, escAir = 0, escCooldown = 0;

    private static BlockPos currentTarget = null;
    private static int mineTicks = 0;
    private static final Set<BlockPos> skip = new HashSet<>();
    private static final List<BlockPos> rem = new ArrayList<>();
    private static int scanCd = 0;

    private static int pickSlot = -1, shovelSlot = -1;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static int stillTicks = 0;
    private static int hudTick = 0;
    private static int holeTicks = 0;
    private static int escCount = 0;
    private static int placeCd = 0;
    private static boolean placeAimed = false;
    private static final int PLACE_DELAY = 6;   // tick giua 2 lan dat block (6 tick = 0.3s)
    private static long escWindowStart = 0;

    private static boolean eating = false;
    private static int eatTicks = 0, eatCooldown = 0;

    // ===== Sua cup bang binh XP (Mending: cup phai nam o tay phu) =====
    private static final float REPAIR_TARGET = 98f;
    private static boolean repairing = false, repairSwapped = false, repairEnding = false, noXpWarned = false;
    private static int repairTicks = 0, repairWait = 0, repairSwapTries = 0, repairBackTries = 0, repairCooldown = 0;

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
        holeTicks = 0;
        repairing = false;
        repairCooldown = 0;
        noXpWarned = false;
        escCount = 0;
        escWindowStart = 0;
        eating = false;
        escaping = false;
        escCooldown = 0;
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
        if (repairing && repairSwapped && mc.player != null) {   // tra cup ve tay chinh
            mc.player.getInventory().setSelectedSlot(pickSlot);
            sendSwapOffhand(mc);
        }
        repairing = false;
        enabled = false;
        escaping = false;
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

        if (repairCooldown > 0) repairCooldown--;
        if (placeCd > 0) placeCd--;
        if (repairing) {            // dang nem binh XP: dung moi viec khac
            doRepair(mc, p, im);
            return;
        }
        if (eatCooldown > 0) eatCooldown--;
        if (escCooldown > 0) escCooldown--;

        // 1. An
        if (!autoEat && eating) stopEating(mc);
        if (autoEat && handleEating(mc, p, im)) return;

        // 2. Cong cu + do ben
        PlayerInventory inv = p.getInventory();
        pickSlot = resolveMainPick(p, pickSlot);
        if (pickSlot < 0) {
            disable(mc, "Het cuc! Da dung.");
            return;
        }
        ItemStack pick = inv.getStack(pickSlot);
        if (durabilityPercent(pick) < DURABILITY_THRESHOLD) {
            if (useXpBottle && repairCooldown == 0 && findXpSlot(p) >= 0) {
                startRepair(mc, p, im);
                return;
            }
            if (pick.isDamageable() && pick.getMaxDamage() - pick.getDamage() <= 2) {
                disable(mc, "Cuc sap hong va het binh XP! Da dung.");
                return;
            }
        }
        shovelSlot = resolveTool(p, ItemTags.SHOVELS, shovelSlot);
        if (shovelSlot >= 0) {
            ItemStack sh = inv.getStack(shovelSlot);
            if (sh.isDamageable() && sh.getMaxDamage() - sh.getDamage() <= 2) shovelSlot = -1;
        }

        // 3. Roi xuong ho SAU (>= 2 block thap hon tang dang dao) -> doi cup thuong, dao bac thang len.
        //    Ho nong 1 block thi bot tu nhay len khi di chuyen, khong can thoat ho.
        if (!escaping && escCooldown == 0 && !descending && p.isOnGround()
            && p.getBlockPos().getY() <= groupBottom - 2) {
            startEscape(mc, p);
            if (!enabled) return;
        }

        // 4. Scaffold (chi khi dang dao ngang)
        BlockPos below = p.getBlockPos().down();
        if (autoScaffold && !descending && !escaping && !p.getAbilities().flying && w.getBlockState(below).isAir()) {
            im.cancelBlockBreaking();
            if (placeAt(p, w, im, below)) return;
        }

        hud(p);

        if (escaping) doEscape(mc, p, w, im);
        else if (descending) doDescend(mc, p, w, im);
        else doWork(mc, p, w, im);
    }

    // ===== Dao 1 tang =====
    private static void doWork(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w, ClientPlayerInteractionManager im) {
        // tiep tuc block dang dao
        if (currentTarget != null) {
            if (mineable(w, currentTarget)
                && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(currentTarget)) <= REACH_SQ) {
                releaseMove(mc);
                mineBlock(mc, p, w, im, currentTarget, true);
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
            if (curLane >= 0) {
                // bat dau tu dau hang gan nguoi nhat -> hang ke tiep tu dong quet nguoc lai (zigzag)
                laneDir = chooseLaneDir(curLane, alongX ? p.getX() : p.getZ());
            }
        }

        // Cup 3x3: moi nhat dao 1 block GIUA cua lat cat 3x3 se vo ca 9 o.
        // -> dao tung lat cat mot theo huong quet; LUON dung mat ngang (khong bao gio mat tren/duoi)
        //    va chi dao hang giua (centerY) de 3x3 khong an vao san.
        int sliceA = nextSlice();
        if (sliceA == Integer.MIN_VALUE) return;
        BlockPos target = sliceTarget(sliceA);
        if (target != null && !canMineSafely(w, p, target)) {
            // block lech hang giua ma khong co cup thuong/xeng -> bo qua de khong pha san
            skip.add(target);
            rem.remove(target);
            return;
        }
        if (target != null && p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= REACH_SQ) {
            stillTicks = 0;
            releaseMove(mc);
            currentTarget = target;
            mineTicks = 0;
            mineBlock(mc, p, w, im, target, true);
            return;
        }

        // chua toi tam -> di toi lat cat do (chi di tren cho da dao xong)
        im.cancelBlockBreaking();
        if (!botMove) return;

        double lc = laneCenter(curLane);
        double tx = alongX ? sliceA + 0.5 : lc;
        double tz = alongX ? lc : sliceA + 0.5;
        if (guardHole(mc, p, w, im, tx, tz)) return;
        walkTo(mc, p, tx, tz);
        BlockPos near = target;

        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        if (pos.squaredDistanceTo(lastPos) < 0.0004) stillTicks++;
        else stillTicks = 0;
        lastPos = pos;
        // ket trong ho 1 block (khong tu nhay ra duoc) -> thoat ho bang cup thuong
        if (stillTicks >= 40 && escCooldown == 0 && p.getBlockPos().getY() < groupBottom) {
            startEscape(mc, p);
            return;
        }
        if (stillTicks == 100 && near != null) {
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
        mineBlock(mc, p, w, im, target, false);
    }

    /** safe=true: dao theo lat cat -> mat dap LUON la mat ngang, nhin ngang; block lech hang giua dung cup thuong. */
    private static void mineBlock(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w,
                                  ClientPlayerInteractionManager im, BlockPos target, boolean safe) {
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
        int pk = (escaping && escSlot >= 0) ? escSlot : pickSlot;
        if (safe && target.getY() != centerY()) {
            int np = resolveNormalPick(p);
            if (np >= 0) pk = np;
        }
        int slot = (s.isIn(BlockTags.SHOVEL_MINEABLE) && shovelSlot >= 0) ? shovelSlot : pk;
        PlayerInventory inv = p.getInventory();
        if (inv.getSelectedSlot() != slot) inv.setSelectedSlot(slot);

        Vec3d eye = p.getEyePos();
        Vec3d c = Vec3d.ofCenter(target);
        Direction face;
        if (safe) {
            double fx = eye.x - c.x, fz = eye.z - c.z;
            face = Math.abs(fx) >= Math.abs(fz)
                ? (fx >= 0 ? Direction.EAST : Direction.WEST)
                : (fz >= 0 ? Direction.SOUTH : Direction.NORTH);
            aim(p, c.add(face.getOffsetX() * 0.45, 0, face.getOffsetZ() * 0.45));
            p.setPitch(Math.max(-12f, Math.min(12f, p.getPitch())));   // nhin ngang, khong cui/ngua
        } else {
            aim(p, c);
            face = Direction.getFacing(eye.x - c.x, eye.y - c.y, eye.z - c.z);
        }
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

    // ===== Roi xuong ho: doi cup thuong, dao bac thang cheo len =====
    private static void startEscape(MinecraftClient mc, ClientPlayerEntity p) {
        // Chong vong lap: roi ho -> len -> lai roi cung cho. 4 lan trong 2 phut thi dung han va bao toa do.
        long now = System.currentTimeMillis();
        if (now - escWindowStart > 120_000L) {
            escWindowStart = now;
            escCount = 0;
        }
        if (++escCount > 3) {
            disable(mc, "Roi xuong ho 4 lan trong 2 phut tai " + p.getBlockPos().toShortString()
                + " (tang dao Y=" + groupBottom + "). San dang bi thung sau -> lap ho (hoac de block trong hotbar + bat Scaffold) roi chay lai.");
            return;
        }
        escaping = true;
        escTries = 0;
        escAir = 0;
        currentTarget = null;
        mineTicks = 0;
        stillTicks = 0;
        releaseMove(mc);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        escSlot = resolveNormalPick(p);
        double cx = (minX + maxX + 1) / 2.0, cz = (minZ + maxZ + 1) / 2.0;
        double dx = cx - p.getX(), dz = cz - p.getZ();
        escDir = Math.abs(dx) >= Math.abs(dz)
            ? (dx >= 0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        AutoMinerClient.msg(escSlot >= 0
            ? "Roi xuong ho tai " + p.getBlockPos().toShortString() + " (tang Y=" + groupBottom + ")! Doi sang cup thuong de dao bac thang len."
            : "Roi xuong ho! Chua biet cup thuong nao (cam cup thuong roi go /am normal), tam dung cup hien tai.");
    }

    private static void endEscape(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        escaping = false;
        releaseMove(mc);
        im.cancelBlockBreaking();
        currentTarget = null;
        mineTicks = 0;
        stillTicks = 0;
        escCooldown = 60;   // 3 giay khong cho thoat ho lai ngay -> tranh lap
        rem.clear();
        scanCd = 0;
        if (pickSlot >= 0) p.getInventory().setSelectedSlot(pickSlot);
        AutoMinerClient.msg("Da len lai tang dao, tiep tuc.");
    }

    private static void rotateEscape(MinecraftClient mc) {
        releaseMove(mc);
        escDir = escDir.rotateYClockwise();
        currentTarget = null;
        mineTicks = 0;
        stillTicks = 0;
        if (++escTries > 12) disable(mc, "Khong tim duoc duong len, da dung.");
    }

    private static void doEscape(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w, ClientPlayerInteractionManager im) {
        if (!p.isOnGround()) {
            if (++escAir < 40) return;
        } else {
            escAir = 0;
        }
        BlockPos feet = p.getBlockPos();
        if (feet.getY() >= groupBottom) {
            endEscape(mc, p, im);
            return;
        }

        // bac thang cheo: can thong 3 o = tren dau, o buoc len, tren o buoc len
        BlockPos step = feet.offset(escDir).up();
        BlockPos[] need = {feet.up(2), step.up(), step};
        for (BlockPos c : need) {
            BlockState s = w.getBlockState(c);
            if (s.isAir()) continue;
            if (w.getFluidState(c).isIn(FluidTags.LAVA)) {
                rotateEscape(mc);
                return;
            }
            if (!w.getFluidState(c).isEmpty()) continue;
            if (s.getHardness(w, c) < 0 || skip.contains(c)) {
                rotateEscape(mc);
                return;
            }
            releaseMove(mc);
            if (currentTarget == null || !currentTarget.equals(c)) {
                currentTarget = c;
                mineTicks = 0;
            }
            mineBlock(mc, p, w, im, c);
            return;
        }

        // da thong -> nhay len bac
        im.cancelBlockBreaking();
        currentTarget = null;
        walkTo(mc, p, step.getX() + 0.5, step.getZ() + 0.5);
        mc.options.jumpKey.setPressed(p.isOnGround());
        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        if (pos.squaredDistanceTo(lastPos) < 0.0004) stillTicks++;
        else stillTicks = 0;
        lastPos = pos;
        if (stillTicks > 30) rotateEscape(mc);
    }

    // ===== Nhan dang cup 3x3 / cup thuong =====
    private static boolean mentions3x3(String str) {
        String n = str.toLowerCase().replace(" ", "");
        return n.contains("3x3") || n.contains("3*3") || n.contains("3×3");
    }

    private static boolean isAreaPick(ItemStack s) {
        if (!s.isIn(ItemTags.PICKAXES)) return false;
        if (mentions3x3(s.getName().getString())) return true;
        LoreComponent lore = s.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text line : lore.lines()) {
                if (mentions3x3(line.getString())) return true;
            }
        }
        return false;
    }

    /** O cup thuong: do nguoi dung dat (/am normal) hoac tu nhan dang (co cup 3x3 + cup khong ghi 3x3). -1 neu khong ro. */
    private static int resolveNormalPick(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        if (normalPickSlot >= 0 && normalPickSlot < 9 && inv.getStack(normalPickSlot).isIn(ItemTags.PICKAXES)) {
            return normalPickSlot;
        }
        boolean hasArea = false;
        int plain = -1;
        float bp = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isIn(ItemTags.PICKAXES)) continue;
            if (isAreaPick(s)) {
                hasArea = true;
            } else {
                float pp = durabilityPercent(s);
                if (pp > bp) {
                    bp = pp;
                    plain = i;
                }
            }
        }
        return hasArea ? plain : -1;
    }

    /** Cup dung de dao chinh: loai tru cup thuong (neu chi co 1 cup thuong thi van dung no). */
    private static int findMainPick(ClientPlayerEntity p, int exclude) {
        PlayerInventory inv = p.getInventory();
        int best = -1;
        float bp = -1;
        for (int i = 0; i < 9; i++) {
            if (i == exclude) continue;
            ItemStack s = inv.getStack(i);
            if (!s.isIn(ItemTags.PICKAXES)) continue;
            float pp = durabilityPercent(s);
            if (pp > bp) {
                bp = pp;
                best = i;
            }
        }
        if (best < 0 && exclude >= 0) best = exclude;
        return best;
    }

    private static int resolveMainPick(ClientPlayerEntity p, int cur) {
        int ex = resolveNormalPick(p);
        if (cur >= 0 && cur != ex) {
            ItemStack s = p.getInventory().getStack(cur);
            if (s.isIn(ItemTags.PICKAXES) && durabilityPercent(s) >= DURABILITY_THRESHOLD) return cur;
        }
        return findMainPick(p, ex);
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

    /** Thu tu quet doc hang: so nho hon = dao truoc. */
    private static int alongKey(BlockPos b) {
        return (alongX ? b.getX() : b.getZ()) * laneDir;
    }

    /** Chon huong quet: bat dau tu dau hang gan nguoi choi nhat. */
    private static int chooseLaneDir(int lane, double pAlong) {
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (BlockPos b : rem) {
            if (laneOf(b) != lane) continue;
            int a = alongX ? b.getX() : b.getZ();
            if (a < lo) lo = a;
            if (a > hi) hi = a;
        }
        if (lo > hi) return 1;
        return Math.abs(pAlong - (lo + 0.5)) <= Math.abs(pAlong - (hi + 0.5)) ? 1 : -1;
    }

    private static double laneCenter(int i) {
        int width = Math.min(3, (latMax - latMin + 1) - 3 * i);
        return latMin + 3 * i + (width - 1) / 2.0 + 0.5;
    }

    // ===== Lat cat / hang giua =====
    /** Hang Y giua cua nhom tang: cup 3x3 dao o day thi chi an tang duoi chan..dau, khong an san. */
    private static int centerY() {
        return groupBottom + 1;
    }

    /** Lat cat (toa do doc huong di) gan nhat phia truoc theo huong quet trong hang hien tai. */
    private static int nextSlice() {
        int best = Integer.MIN_VALUE;
        long bestKey = Long.MAX_VALUE;
        for (BlockPos b : rem) {
            if (laneOf(b) != curLane) continue;
            int a = alongX ? b.getX() : b.getZ();
            long key = (long) a * laneDir;
            if (key < bestKey) {
                bestKey = key;
                best = a;
            }
        }
        return best;
    }

    /** Block cua lat cat a: uu tien hang giua (centerY) va nam sat tam hang nhat. */
    private static BlockPos sliceTarget(int a) {
        BlockPos best = null;
        double bs = 1e18;
        double lc = laneCenter(curLane);
        for (BlockPos b : rem) {
            if (laneOf(b) != curLane) continue;
            if ((alongX ? b.getX() : b.getZ()) != a) continue;
            double lat = (alongX ? b.getZ() : b.getX()) + 0.5;
            double score = Math.abs(lat - lc) + (b.getY() == centerY() ? 0 : 10);
            if (score < bs) {
                bs = score;
                best = b;
            }
        }
        return best;
    }

    /** Block lech hang giua chi duoc dao khi co cup thuong (hoac xeng cho dat/cat), neu khong se bi 3x3 an san. */
    private static boolean canMineSafely(ClientWorld w, ClientPlayerEntity p, BlockPos b) {
        if (b.getY() == centerY()) return true;
        if (resolveNormalPick(p) >= 0) return true;
        return shovelSlot >= 0 && w.getBlockState(b).isIn(BlockTags.SHOVEL_MINEABLE);
    }

    /** Truoc mat la o trong ma khong co san (ho/lava) -> lap san, neu khong lap duoc thi dung lai, khong buoc xuong. */
    private static boolean guardHole(MinecraftClient mc, ClientPlayerEntity p, ClientWorld w,
                                     ClientPlayerInteractionManager im, double tx, double tz) {
        double dx = tx - p.getX(), dz = tz - p.getZ();
        Direction d = Math.abs(dx) >= Math.abs(dz)
            ? (dx >= 0 ? Direction.EAST : Direction.WEST)
            : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos ahead = p.getBlockPos().offset(d);
        if (!w.getBlockState(ahead).isAir()) { holeTicks = 0; return false; }   // dang co block -> bot se dao, khong di
        BlockPos floor = ahead.down();
        boolean hole = w.getBlockState(floor).isAir() || w.getFluidState(floor).isIn(FluidTags.LAVA);
        if (!hole) { holeTicks = 0; return false; }
        releaseMove(mc);
        im.cancelBlockBreaking();
        if (autoScaffold && placeAt(p, w, im, floor)) return true;
        if (++holeTicks > 60) disable(mc, "Truoc mat la ho va khong co block de lap! Da dung.");
        return true;
    }

    // ===== Sua cup bang binh XP =====
    private static int findXpSlot(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.EXPERIENCE_BOTTLE)) return i;
        }
        return -1;
    }

    private static void sendSwapOffhand(MinecraftClient mc) {
        if (mc.getNetworkHandler() == null) return;
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }

    private static void startRepair(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        repairing = true;
        repairSwapped = false;
        repairEnding = false;
        repairTicks = 0;
        repairSwapTries = 0;
        repairBackTries = 0;
        repairWait = 3;
        releaseMove(mc);
        im.cancelBlockBreaking();
        currentTarget = null;
        mineTicks = 0;
        stillTicks = 0;
        p.getInventory().setSelectedSlot(pickSlot);
        AutoMinerClient.msg("Cuc yeu -> nem binh XP de sua (Mending).");
    }

    private static void doRepair(MinecraftClient mc, ClientPlayerEntity p, ClientPlayerInteractionManager im) {
        PlayerInventory inv = p.getInventory();
        releaseMove(mc);
        im.cancelBlockBreaking();
        if (++repairTicks > 20 * 120) repairEnding = true;   // qua lau -> thoi
        if (repairWait > 0) {
            repairWait--;
            return;
        }

        // 1. Chuyen cup sang tay phu (Mending chi sua do dang cam / tay phu / giap)
        if (!repairSwapped && !repairEnding) {
            if (p.getOffHandStack().isIn(ItemTags.PICKAXES)) {
                repairSwapped = true;
            } else if (repairSwapTries < 3) {
                inv.setSelectedSlot(pickSlot);
                sendSwapOffhand(mc);
                repairSwapTries++;
                repairWait = 6;
                return;
            } else {
                repairing = false;
                repairCooldown = 20 * 30;
                AutoMinerClient.msg("Khong chuyen duoc cup sang tay phu, bo qua sua cup.");
                return;
            }
        }

        // 2. Ket thuc: tra cup ve o hotbar cu
        if (repairEnding) {
            if (!repairSwapped || inv.getStack(pickSlot).isIn(ItemTags.PICKAXES)) {
                repairing = false;
                repairSwapped = false;
                repairCooldown = 40;
                inv.setSelectedSlot(pickSlot);
                AutoMinerClient.msg("Xong sua cup, tiep tuc dao.");
                return;
            }
            if (repairBackTries++ > 4) {
                disable(mc, "Khong tra duoc cup ve tay chinh, da dung.");
                return;
            }
            inv.setSelectedSlot(pickSlot);
            sendSwapOffhand(mc);
            repairWait = 6;
            return;
        }

        // 3. Nem binh XP xuong chan cho toi khi cup day
        if (durabilityPercent(p.getOffHandStack()) >= REPAIR_TARGET) {
            repairEnding = true;
            return;
        }
        int xs = findXpSlot(p);
        if (xs < 0) {
            if (!noXpWarned) {
                noXpWarned = true;
                AutoMinerClient.msg("Het binh XP trong hotbar!");
            }
            repairEnding = true;
            return;
        }
        if (inv.getSelectedSlot() != xs) {
            inv.setSelectedSlot(xs);
            repairWait = 2;
            return;
        }
        p.setPitch(90f);
        if (repairTicks % 3 == 0) {
            im.interactItem(p, Hand.MAIN_HAND);
            p.swingHand(Hand.MAIN_HAND);
        }
    }

    private static void hud(ClientPlayerEntity p) {
        if (hudTick++ % 20 != 0) return;
        int total = (int) Math.ceil((maxY - minY + 1) / (double) groupH);
        int idx = (maxY - groupTop()) / groupH + 1;
        p.sendMessage(Text.literal("Tang " + idx + "/" + total
            + " | Hang " + (curLane + 1) + "/" + laneCount
            + " | Con " + rem.size() + " block"
            + (escaping ? " | dang thoat ho" : descending ? " | dang xuong tang" : "")), true);
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
        if (placeCd > 0) return true;                 // dang cho giua 2 lan dat -> dung yen, doi
        if (p.getInventory().getSelectedSlot() != slot) {
            p.getInventory().setSelectedSlot(slot);   // doi o hotbar truoc, doi server nhan roi moi dat
            placeCd = 3;
            return true;
        }
        Vec3d eye = p.getEyePos();
        for (Direction d : Direction.values()) {
            BlockPos support = target.offset(d);
            BlockState s = w.getBlockState(support);
            if (s.isAir() || !w.getFluidState(support).isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3d hitVec = Vec3d.ofCenter(support).add(
                face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            if (eye.squaredDistanceTo(hitVec) > REACH_SQ) continue;

            aim(p, hitVec);
            if (!placeAimed) {            // quay dau truoc, doi server nhan huong nhin roi moi dat
                placeAimed = true;
                placeCd = 2;
                return true;
            }
            placeAimed = false;
            BlockHitResult hit = new BlockHitResult(hitVec, face, support, false);
            ActionResult r = im.interactBlock(p, Hand.MAIN_HAND, hit);
            if (r.isAccepted()) p.swingHand(Hand.MAIN_HAND);
            placeCd = PLACE_DELAY;
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
