package com.example.autominer;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Ve vien mau quanh vung chon bang hat bui mau. */
public final class RegionOutline {
    private static int counter = 0;
    private static final DustParticleEffect RED = new DustParticleEffect(0xFF2020, 1.0f);
    private static final DustParticleEffect GREEN = new DustParticleEffect(0x20FF20, 1.0f);
    private static final DustParticleEffect YELLOW = new DustParticleEffect(0xFFE020, 1.0f);

    private RegionOutline() {}

    public static void tick(MinecraftClient mc) {
        ClientWorld w = mc.world;
        if (w == null || mc.player == null) return;
        if (++counter % 4 != 0) return;

        Vec3d me = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        BlockPos a = MinerLogic.pos1, b = MinerLogic.pos2;
        if (a != null && b == null) box(w, me, a, a, YELLOW);
        else if (a == null && b != null) box(w, me, b, b, YELLOW);
        else if (a != null) box(w, me, a, b, RED);

        BlockPos[] g = MinerLogic.activeBox();
        if (g != null) box(w, me, g[0], g[1], GREEN);
    }

    private static void box(ClientWorld w, Vec3d me, BlockPos p, BlockPos q, DustParticleEffect fx) {
        double x0 = Math.min(p.getX(), q.getX()), x1 = Math.max(p.getX(), q.getX()) + 1;
        double y0 = Math.min(p.getY(), q.getY()), y1 = Math.max(p.getY(), q.getY()) + 1;
        double z0 = Math.min(p.getZ(), q.getZ()), z1 = Math.max(p.getZ(), q.getZ()) + 1;
        // 4 canh theo X
        line(w, me, fx, x0, y0, z0, x1, y0, z0);
        line(w, me, fx, x0, y1, z0, x1, y1, z0);
        line(w, me, fx, x0, y0, z1, x1, y0, z1);
        line(w, me, fx, x0, y1, z1, x1, y1, z1);
        // 4 canh theo Y
        line(w, me, fx, x0, y0, z0, x0, y1, z0);
        line(w, me, fx, x1, y0, z0, x1, y1, z0);
        line(w, me, fx, x0, y0, z1, x0, y1, z1);
        line(w, me, fx, x1, y0, z1, x1, y1, z1);
        // 4 canh theo Z
        line(w, me, fx, x0, y0, z0, x0, y0, z1);
        line(w, me, fx, x1, y0, z0, x1, y0, z1);
        line(w, me, fx, x0, y1, z0, x0, y1, z1);
        line(w, me, fx, x1, y1, z0, x1, y1, z1);
    }

    private static void line(ClientWorld w, Vec3d me, DustParticleEffect fx,
                             double ax, double ay, double az, double bx, double by, double bz) {
        double len = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        int n = (int) Math.max(1, Math.ceil(len / 0.5));
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            double x = ax + (bx - ax) * t, y = ay + (by - ay) * t, z = az + (bz - az) * t;
            double dx = x - me.x, dy = y - me.y, dz = z - me.z;
            if (dx * dx + dy * dy + dz * dz > 28 * 28) continue;
            w.addParticleClient(fx, x, y, z, 0, 0, 0);
        }
    }
}
