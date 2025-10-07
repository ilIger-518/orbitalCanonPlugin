package de.smokingpilliger.orbital;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.*;

public class OrbitalStrikePlugin extends JavaPlugin implements Listener {

    private NamespacedKey cannonKey;
    private NamespacedKey projectileKey;
    // cooldown in milliseconds
    private final long COOLDOWN_MS = 5000;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

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

        // small visual + sound when firing
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
        final int STRIKES = 4;      // how many concentrated explosions
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
                // spawn high-altitude beam and circle effect
                Location top = strikeLoc.clone().add(0, 30, 0);

                // beam effect (5-block wide cylinder)
                double beamRadius = 2.5;
                for (double y = top.getY(); y >= strikeLoc.getY(); y -= 0.3) {
                    for (double angle = 0; angle < 2 * Math.PI; angle += Math.PI / 8) {
                        double x = Math.cos(angle) * beamRadius;
                        double z = Math.sin(angle) * beamRadius;
                        Location beamLoc = new Location(world, strikeLoc.getX() + x, y, strikeLoc.getZ() + z);
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME, beamLoc, 2, 0, 0, 0, 0);
                        world.spawnParticle(Particle.END_ROD, beamLoc, 1, 0, 0, 0, 0);
                    }
                }

                // circle effect on ground
                int points = 40;
                double radius = 3.0;
                for (int j = 0; j < points; j++) {
                    double angle = 2 * Math.PI * j / points;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location circleLoc = strikeLoc.clone().add(x, 0.1, z);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME, circleLoc, 1, 0, 0, 0, 0);
                }

                world.playSound(strikeLoc, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 2.0f, 1.5f);

                // small delay before explosion "impact" particle drop
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        // final impact: explosion with block damage enabled
                        // (power, setFire, breakBlocks)
                        world.createExplosion(strikeLoc, EXPLOSION_POWER, false, true);

                        // randomly set some nearby blocks on fire (about half)
                        int fireRadius = 3;
                        for (int dx = -fireRadius; dx <= fireRadius; dx++) {
                            for (int dz = -fireRadius; dz <= fireRadius; dz++) {
                                if (Math.random() < 0.5) {
                                    Location fireLoc = strikeLoc.clone().add(dx, 0, dz);
                                    if (world.getBlockAt(fireLoc).getType().isAir() && world.getBlockAt(fireLoc.clone().add(0, -1, 0)).getType().isSolid()) {
                                        world.getBlockAt(fireLoc).setType(Material.FIRE);
                                    }
                                }
                            }
                        }

                        // additional visual
                        world.spawnParticle(Particle.EXPLOSION, strikeLoc, 1);
                        world.playSound(strikeLoc, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4f, 1f);
                    }
                }.runTaskLater(this, 2L + index); // tiny stagger
            }, i * TICKS_BETWEEN);
        }
    }
}