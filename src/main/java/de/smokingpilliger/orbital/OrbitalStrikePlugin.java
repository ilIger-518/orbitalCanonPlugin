package de.smokingpilliger.orbital;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;

import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

public class OrbitalStrikePlugin extends JavaPlugin implements Listener {

    private NamespacedKey cannonKey;
    private NamespacedKey projectileKey;
    // cooldown in milliseconds
    private final long COOLDOWN_MS = 500;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    // === Beam config ===
    private static final int   BEAM_DURATION_TICKS = 20 * 5;
    private static final double BEAM_RADIUS_BLOCKS = 4.0;
    private static final Material BEAM_MATERIAL = Material.LIGHT_BLUE_STAINED_GLASS;

    @Override
    public void onEnable() {
        cannonKey = new NamespacedKey(this, "orbital_cannon");
        projectileKey = new NamespacedKey(this, "orbital_projectile");
        getServer().getPluginManager().registerEvents(this, this);

        // register simple command to give the item
        Objects.requireNonNull(this.getCommand("orbitalgun")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Only players can receive the Orbital Strike Cannon.");
                return true;
            }

            Player p = (Player) sender;
            if (!p.hasPermission("orbitalstrike.give") && !p.isOp()) {
                p.sendMessage("You don't have permission.");
                return true;
            }

            p.getInventory().addItem(createOrbitalCannon());
            p.sendMessage("You received the Orbital Strike Cannon.");
            return true;
        });

        getLogger().info("OrbitalStrikePlugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("OrbitalStrikePlugin disabled.");
    }

    private ItemStack createOrbitalCannon() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName("§cOrbital Strike Cannon");
        List<String> lore = new ArrayList<>();
        lore.add("§7Right-click to fire an orbital strike projectile.");
        lore.add("§7Explosions on impact. Use with caution.");
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.addEnchant(Enchantment.LOYALTY, 1, true); // purely cosmetic enchant look
        // persistent tag
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(cannonKey, PersistentDataType.BYTE, (byte)1);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isOrbitalCannon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte val = pdc.get(cannonKey, PersistentDataType.BYTE);
        return val != null && val == (byte)1;
    }

    @EventHandler
    public void onPlayerUse(PlayerInteractEvent event) {
        if (event.getItem() == null) return;
        if (!isOrbitalCannon(event.getItem())) return;

        // only right clicks
        switch (event.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                break;
            default:
                return;
        }

        event.setCancelled(true); // prevent other interactions while using

        Player player = event.getPlayer();
        // permission check
        if (!player.hasPermission("orbitalstrike.use") && !player.isOp()) {
            player.sendMessage("You don't have permission to use the Orbital Strike Cannon.");
            return;
        }

        // cooldown
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            long remain = (COOLDOWN_MS - (now - last)) / 1000 + 1;
            player.sendMessage("Orbital Cannon recharging... " + remain + "s");
            return;
        }
        cooldowns.put(player.getUniqueId(), now);

        // Launch a snowball as the "projectile"
        Snowball sb = player.launchProjectile(Snowball.class);
        // mark the projectile in PersistentDataContainer
        PersistentDataContainer pdc = sb.getPersistentDataContainer();
        pdc.set(projectileKey, PersistentDataType.STRING, player.getUniqueId().toString());

        // small visual + sound when firing (still fine to keep a tiny spark)
        player.getWorld().spawnParticle(Particle.CRIT, player.getEyeLocation().add(player.getLocation().getDirection().multiply(1)), 8, 0.2, 0.2, 0.2, 0.05);
        player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 1f, 1f);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball sb = (Snowball) event.getEntity();
        PersistentDataContainer pdc = sb.getPersistentDataContainer();
        String shooterUuid = pdc.get(projectileKey, PersistentDataType.STRING);
        if (shooterUuid == null) return; // not our projectile

        Location hitLoc = event.getHitEntity() != null ? event.getHitEntity().getLocation() : sb.getLocation();
        World world = hitLoc.getWorld();
        if (world == null) return;

        // remove the projectile
        sb.remove();

        // Number of strikes and timing - defaults
        final int STRIKES = 2;       // how many concentrated explosions
        final int TICKS_BETWEEN = 8; // ticks between strikes (8 ticks = 0.4s)
        final double RADIUS = 1.5;   // spread radius for multi-strike pattern
        final float EXPLOSION_POWER = 15.0f; // explosion strength

        // Inform shooter if online
        Player shooter = Bukkit.getPlayer(UUID.fromString(shooterUuid));
        if (shooter != null) shooter.sendMessage("Orbital strike initiated.");

        // Schedule repeated strikes
        for (int i = 0; i < STRIKES; i++) {
            final int index = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                // small random offset per strike to make it feel dynamic
                double ox = (Math.random() - 0.5) * RADIUS;
                double oz = (Math.random() - 0.5) * RADIUS;
                Location strikeLoc = hitLoc.clone().add(ox, 0, oz);
                World w = world;

                // Beam lifetime (5 seconds, same as BEAM_DURATION_TICKS)
                final int topY = Math.min(w.getMaxHeight() - 1, strikeLoc.getBlockY() + 256);

                // 1) Spawn our custom blue beam (display entity)
                BlockDisplay beam = spawnCustomBeam(strikeLoc, topY, BEAM_RADIUS_BLOCKS, BEAM_MATERIAL);

                // cue
                w.playSound(strikeLoc, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 2.0f, 1.2f);

                // 2) After life: remove beam & big impact
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        if (beam != null && !beam.isDead()) {
                            beam.remove();
                        }

                        // Big impact (with block damage)
                        w.createExplosion(strikeLoc, EXPLOSION_POWER, false, true);

                        // randomly set some nearby blocks on fire (about half)
                        int fireRadius = 3;
                        for (int dx = -fireRadius; dx <= fireRadius; dx++) {
                            for (int dz = -fireRadius; dz <= fireRadius; dz++) {
                                if (Math.random() < 0.5) {
                                    Location fireLoc = strikeLoc.clone().add(dx, 0, dz);
                                    if (w.getBlockAt(fireLoc).getType().isAir() && w.getBlockAt(fireLoc.clone().add(0, -1, 0)).getType().isSolid()) {
                                        w.getBlockAt(fireLoc).setType(Material.FIRE);
                                    }
                                }
                            }
                        }

                        // additional visual + audio
                        w.spawnParticle(Particle.EXPLOSION, strikeLoc, 1);
                        w.playSound(strikeLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4f, 1f);
                    }
                }.runTaskLater(this, BEAM_DURATION_TICKS);

            }, i * TICKS_BETWEEN);
        }
    }

    // === Custom beam implementation (no particles, no real beacons) ===
    // Uses a single BlockDisplay stretched into a tall translucent column.

    /**
     * Spawns a tall blue beam using a BlockDisplay scaled to the desired radius and height.
     * @param base The base (x,z from this, y is ground level)
     * @param topY The Y to reach (usually near build height)
     * @param radiusBlocks Radius in blocks (X/Z)
     * @param material Translucent material (e.g., BLUE_STAINED_GLASS)
     * @return The spawned BlockDisplay entity
     */
    private BlockDisplay spawnCustomBeam(Location base, int topY, double radiusBlocks, Material material) {
        World w = base.getWorld();
        if (w == null) return null;

        int by = base.getBlockY();
        double height = Math.max(1.0, (topY - by + 1));
        double diameter = radiusBlocks * 2.0;

        // Spawn at the center of the target block; we'll scale & translate it up.
        Location spawnLoc = base.clone().add(0.5, 0, 0.5);

        BlockData data = material.createBlockData();
        BlockDisplay display = w.spawn(spawnLoc, BlockDisplay.class, d -> {
            d.setBlock(data);

            // Keep it bright and visible
            d.setBrightness(new Display.Brightness(15, 15)); // max
            d.setGlowing(false); // beam is bright enough; toggle to true if you want outline
            d.setBillboard(Display.Billboard.FIXED);

            // View range so players can see it from far away
            d.setViewRange(128.0f);
            d.setShadowRadius(0f);
            d.setShadowStrength(0f);
            d.setTeleportDuration(1); // smooth spawn

            // Build a transformation that scales the 1x1x1 "block cube" into a tall column,
            // then lifts it up by half its height so it starts at ground level.
            Vector3f scale = new Vector3f((float) diameter, (float) height, (float) diameter);
            // translation is *local* to the display; lift by half the height
            Vector3f translation = new Vector3f(0f, (float) (height / 2.0), 0f);

            Transformation t = new Transformation(
                    translation,
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    scale,
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            );
            d.setTransformation(t);

            // Slight fade in/out for polish (optional; safe defaults if unsupported)
            try {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(5);
            } catch (NoSuchMethodError ignored) {}
        });

        return display;
    }
}
