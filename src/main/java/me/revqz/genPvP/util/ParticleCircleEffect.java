package me.revqz.genPvP.util;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Expanding blue-fire ring at a fixed world location.
 *
 * Particle strategy (the only way to get a trail-free expanding ring):
 *
 *   EXPAND phase → Particle.DUST (cyan, ~1 tick client lifetime)
 *                  Only the current ring edge is ever visible — no trail.
 *
 *   HOLD phase   → Particle.SOUL_FIRE_FLAME (dense, matches the image look)
 *                  Spawned continuously at MAX_RADIUS so the ring stays solid.
 *                  Particle count reduces linearly so the ring fades cleanly.
 *
 * Threading: all math is async; spawnParticle() calls are dispatched to the
 * main thread only when there is something to spawn.
 */
public class ParticleCircleEffect {

    // ── Location ──────────────────────────────────────────────────────────────
    private static final double X = 135.5;
    private static final double Y = 80.05;
    private static final double Z = -126.5;

    // ── Geometry ──────────────────────────────────────────────────────────────
    private static final double MAX_RADIUS = 2.0;

    // ── Ring timing (each step = 2 ticks) ────────────────────────────────────
    /** Steps for the ring to expand 0 → MAX_RADIUS. */
    private static final int EXPAND_STEPS = 18;   // ~1.8 s
    /** Steps the full ring stays solid at MAX_RADIUS. */
    private static final int HOLD_STEPS   = 12;   // ~1.2 s
    /** Steps for the ring to fade out. Shorter = faster despawn. */
    private static final int FADE_STEPS   = 5;    // ~0.5 s  (fast despawn)
    private static final int TOTAL_STEPS  = EXPAND_STEPS + HOLD_STEPS + FADE_STEPS;

    /** New ring spawns every N steps — must be < TOTAL_STEPS for overlap. */
    private static final int SPAWN_INTERVAL = 22;

    // ── Density ───────────────────────────────────────────────────────────────
    /** Arc-length per particle while expanding (larger = fewer particles). */
    private static final double EXPAND_SPACING = 0.55;
    /** Arc-length per particle while holding (tighter = denser ring like image). */
    private static final double HOLD_SPACING   = 0.35;

    // ── Particles ─────────────────────────────────────────────────────────────
    /** Cyan DUST: expires in ~1 tick so zero trail while the ring is moving. */
    private static final Particle.DustOptions CYAN_DUST =
            new Particle.DustOptions(Color.fromRGB(0, 215, 255), 1.25f);

    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

    // ── State (single async thread) ───────────────────────────────────────────
    private final Plugin     plugin;
    private final List<Ring> rings      = new ArrayList<>();
    private       int        step       = 0;
    private       double     ringOffset = 0.0;

    public ParticleCircleEffect(Plugin plugin) {
        this.plugin = plugin;
    }

    // ─────────────────────────────────────────────────────────────────────────

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {

            if (step % SPAWN_INTERVAL == 0) {
                rings.add(new Ring(ringOffset));
                ringOffset += GOLDEN_ANGLE;
            }
            step++;

            // Separate lists so we don't box a type flag per particle
            final List<double[]> dustPositions  = new ArrayList<>(32);
            final List<double[]> flamePositions = new ArrayList<>(48);

            Iterator<Ring> it = rings.iterator();
            while (it.hasNext()) {
                Ring ring = it.next();
                ring.advance();
                if (ring.isDone()) { it.remove(); continue; }

                double radius  = ring.radius();
                if (radius < 0.05) continue;

                Phase  phase   = ring.phase();
                double alpha   = ring.alpha();
                if (alpha <= 0) continue;

                double spacing = (phase == Phase.EXPAND) ? EXPAND_SPACING : HOLD_SPACING;
                int totalPts   = Math.max(4, (int) (2.0 * Math.PI * radius / spacing));
                int spawnPts   = (int) Math.ceil(totalPts * alpha);
                if (spawnPts < 1) continue;

                double angleStep = (2.0 * Math.PI) / totalPts;
                List<double[]> target = (phase == Phase.EXPAND) ? dustPositions : flamePositions;

                for (int i = 0; i < spawnPts; i++) {
                    double angle = ring.offset() + i * angleStep;
                    target.add(new double[]{
                            X + radius * Math.cos(angle),
                            Y,
                            Z + radius * Math.sin(angle)
                    });
                }
            }

            if (dustPositions.isEmpty() && flamePositions.isEmpty()) return;

            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                World world = Bukkit.getWorlds().get(0);
                if (world == null) return;

                // Cyan DUST for the expanding edge (no trail)
                for (double[] p : dustPositions) {
                    world.spawnParticle(Particle.DUST,
                            p[0], p[1], p[2], 1, 0, 0, 0, 0, CYAN_DUST, false);
                }

                // SOUL_FIRE_FLAME for the solid hold + fade ring
                for (double[] p : flamePositions) {
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME,
                            p[0], p[1], p[2], 1, 0, 0, 0, 0.0, null, false);
                }
            });

        }, 2L, 2L);
    }

    // ── Ring ──────────────────────────────────────────────────────────────────

    private enum Phase { EXPAND, HOLD, FADE }

    private static final class Ring {

        private final double startOffset;
        private int age = 0;

        Ring(double startOffset) { this.startOffset = startOffset; }

        void advance() { age++; }
        boolean isDone() { return age >= TOTAL_STEPS; }

        Phase phase() {
            if (age <= EXPAND_STEPS)                     return Phase.EXPAND;
            if (age <= EXPAND_STEPS + HOLD_STEPS)        return Phase.HOLD;
            return Phase.FADE;
        }

        /** Ease-out quad expansion: fast start, decelerates at the edge. */
        double radius() {
            if (age > EXPAND_STEPS) return MAX_RADIUS;
            double t     = (double) age / EXPAND_STEPS;
            double eased = 1.0 - (1.0 - t) * (1.0 - t);
            return MAX_RADIUS * eased;
        }

        /**
         * 1.0 during expand + hold.
         * Drops linearly to 0 over FADE_STEPS for a quick clean despawn.
         */
        double alpha() {
            if (age <= EXPAND_STEPS + HOLD_STEPS) return 1.0;
            return 1.0 - (double)(age - EXPAND_STEPS - HOLD_STEPS) / FADE_STEPS;
        }

        double offset() { return startOffset; }
    }
}
