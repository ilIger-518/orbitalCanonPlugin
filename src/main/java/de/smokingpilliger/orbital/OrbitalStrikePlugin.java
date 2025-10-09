package de.smokingpilliger.orbital;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.*;

public class OrbitalStrikePlugin extends JavaPlugin implements Listener {

    // === Item & tagging ===
    private NamespacedKey gunKey;

    // === Cooldowns (ms) ===
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 2000L; // 2 seconds between shots

    // === Per-player settings (GUI-adjustable) ===
    private final Map<UUID, Integer> strikesSetting = new HashMap<>();
    private final Map<UUID, Boolean> blockDamageSetting = new HashMap<>();
    private final Map<UUID, Float> explosionPowerSetting = new HashMap<>();


    // === Targeting / strike tuning ===
    private static final double MAX_DISTANCE = 300.0; // meters to ray-trace
    private static final double FALL_HEIGHT_ABOVE_MAX = 120.0; // meters above world max height to spawn projectiles
    private static final int    STRIKES = 6;           // number of projectiles per shot
    private static final double STRIKE_SPREAD = 2.5;   // horizontal spread near target
    private static final int    BASE_DELAY_TICKS = 20; // base wait before first impact
    private static final float  EXPLOSION_POWER = 4.0f; // ~TNT strength
    private static final boolean BLOCK_DAMAGE = false; // change to true if you want craters
    private static final boolean SET_FIRE = false;     // explosions set fire?
    private static final boolean DAMAGE_ENTITIES = true;
    private static final double  DAMAGE_RADIUS = 6.0;
    private static final double  PROJECTILE_SPEED = 1.25; // blocks/tick-ish initial impulse

    private static final int STRIKES_DEFAULT = STRIKES; // 6
    private static final boolean BLOCK_DAMAGE_DEFAULT = BLOCK_DAMAGE; // false
    private static final float EXPLOSION_POWER_DEFAULT = EXPLOSION_POWER; // 4.0f

    // Visuals
    private static final Material PROJECTILE_MATERIAL = Material.BLACK_CONCRETE;

    @Override
    public void onEnable() {
        gunKey = new NamespacedKey(this, "orbital_gun_item");
        getServer().getPluginManager().registerEvents(this, this);

        // /orbitalgun give [player]
        Objects.requireNonNull(getCommand("orbitalgun")).setExecutor((sender, command, label, args) -> {
            Player target;
            if (args.length >= 1) {
                target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    sender.sendMessage("Player not found.");
                    return true;
                }
            } else {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Only players can receive the Orbital Rifle.");
                    return true;
                }
                target = p;
            }

            if (!sender.hasPermission("orbitalgun.give") && !sender.isOp()) {
                sender.sendMessage("You don't have permission.");
                return true;
            }

            target.getInventory().addItem(createOrbitalGun());
            sender.sendMessage("Gave Orbital Rifle to " + target.getName());
            return true;
        });

        getLogger().info("OrbitalStrikePlugin enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("OrbitalStrikePlugin disabled.");
    }

    // === Item creation / checks ===

    private ItemStack createOrbitalGun() {
        ItemStack item = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        meta.setDisplayName("§rOrbital Rifle");
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        meta.addEnchant(Enchantment.UNBREAKING, 1, true); // subtle cosmetic glint
        // If you use a resource pack with CustomModelData, set it here:
        meta.setCustomModelData(1234567);

        // Tag the item so only this item fires strikes
        meta.getPersistentDataContainer().set(gunKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isOrbitalGun(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte tag = item.getItemMeta().getPersistentDataContainer().get(gunKey, PersistentDataType.BYTE);
        return tag != null && tag == (byte) 1;
    }

    // === Interaction handler ===

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        Player p = e.getPlayer();
        ItemStack inHand = p.getInventory().getItemInMainHand();
        if (!isOrbitalGun(inHand)) return;

        e.setCancelled(true);

        if (!p.hasPermission("orbitalgun.use") && !p.isOp()) {
            p.sendMessage("You don't have permission to use the Orbital Rifle.");
            return;
        }

        // Sneak + right-click opens the rifle GUI instead of firing
        if (p.isSneaking()) {
            openRifleGui(p);
            return;
        }

        long now = System.currentTimeMillis();
        long end = cooldowns.getOrDefault(p.getUniqueId(), 0L);
        if (now < end) {
            long remainMs = end - now;
            p.spigot().sendMessage(
                net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                new TextComponent("Cooling down… " + Math.max(1, (remainMs + 999) / 1000) + "s")
            );
            return;
        }
        cooldowns.put(p.getUniqueId(), now + COOLDOWN_MS);

        // Ray-trace where the player is aiming
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        World w = p.getWorld();

        RayTraceResult hit = w.rayTrace(eye, dir, MAX_DISTANCE, FluidCollisionMode.NEVER, true, 0.1, entity -> entity != p);
        Location target;
        if (hit != null) {
            if (hit.getHitBlock() != null) {
                target = hit.getHitPosition().toLocation(w);
            } else if (hit.getHitEntity() != null) {
                target = hit.getHitEntity().getLocation();
            } else {
                target = eye.add(dir.multiply(MAX_DISTANCE));
            }
        } else {
            target = eye.add(dir.multiply(MAX_DISTANCE));
        }

        // Small targeting feedback
        w.spawnParticle(Particle.CRIT, target, 12, 0.4, 0.4, 0.4, 0.02);
        w.playSound(p.getLocation(), Sound.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.1f);

        // Kick off the orbital strike
        createOrbitalStrike(p, target);
    }

    // === Orbital strike ===

    private void createOrbitalStrike(Player shooter, Location target) {
        World w = target.getWorld();
        if (w == null) return;

        // Scale initial delay with distance for a more “realistic” feel
        double distance = shooter.getLocation().distance(target);
        int initialDelay = BASE_DELAY_TICKS + (int) Math.min(100, distance / 2.0);

        // Thunder cue before arrival
        Bukkit.getScheduler().runTaskLater(this, () -> {
            w.playSound(target, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 5f, 1.0f);
            w.spawnParticle(Particle.CRIT, target, 60, 0.9, 0.9, 0.9, 0.06);
        }, initialDelay);

        // Fire a volley of projectiles from above build height
        for (int i = 0; i < getStrikes(shooter); i++) {
            final int index = i;
            new BukkitRunnable() {
                @Override
                public void run() {
                    // Randomize around target for spread
                    double ox = (Math.random() - 0.5) * 2 * STRIKE_SPREAD;
                    double oz = (Math.random() - 0.5) * 2 * STRIKE_SPREAD;

                    double spawnY = w.getMaxHeight() + FALL_HEIGHT_ABOVE_MAX;
                    Location spawn = new Location(w, target.getX() + ox, spawnY, target.getZ() + oz);

                    // FallingBlock projectile as a visible kinetic slug
                    FallingBlock fb = w.spawnFallingBlock(spawn, PROJECTILE_MATERIAL.createBlockData());
                    // Prevent placing or dropping items on impact
                    try {
                        fb.setCancelDrop(true); // Paper API
                    } catch (Throwable ignored) {
                        fb.setDropItem(false);  // Fallback on Spigot
                    }
                    fb.setHurtEntities(true);
                    fb.setDamagePerBlock(0.2f);

                    // Aim at target
                    Vector vel = target.toVector().subtract(spawn.toVector()).normalize().multiply(PROJECTILE_SPEED + Math.random() * 0.3);
                    fb.setVelocity(vel);

                    // Smoke trail while in flight
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (fb.isDead() || fb.isOnGround()) {
                                cancel();
                                return;
                            }
                            w.spawnParticle(Particle.SMOKE, fb.getLocation(), 4, 0.18, 0.18, 0.18, 0.01);
                            w.playSound(fb.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, SoundCategory.AMBIENT, 0.15f, 0.6f + (float) Math.random() * 0.2f);
                        }
                    }.runTaskTimer(OrbitalStrikePlugin.this, 0L, 2L);

                    // Poll for arrival / impact
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (fb.isDead() || fb.isOnGround() || fb.getLocation().distanceSquared(target) < 4.0) {
                                Location impact = fb.getLocation();
                                fb.remove();

                                // Impact visuals & audio
                                w.spawnParticle(Particle.EXPLOSION, impact, 1, 0, 0, 0, 0);
                                w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, impact, 20, 0.4, 0.2, 0.4, 0.01);
                                w.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 4f, 0.9f);

                                // The actual explosion (configurable damage to blocks)
                                // Using source == shooter improves knockback consistency on some servers.
                                w.createExplosion(impact, getExplosionPower(shooter), SET_FIRE, getBlockDamage(shooter), shooter);

                                // Optional entity damage pass (adds reliability across forks)
                                if (DAMAGE_ENTITIES) {
                                    double r = DAMAGE_RADIUS;
                                    for (Entity ent : w.getNearbyEntities(impact, r, r, r)) {
                                        if (ent instanceof LivingEntity le) {
                                            if (le.equals(shooter)) continue;
                                            le.damage(10.0, shooter);
                                        }
                                    }
                                }

                                cancel();
                            }
                        }
                    }.runTaskTimer(OrbitalStrikePlugin.this, 0L, 1L);
                }
            }.runTaskLater(this, initialDelay + index * 6L);
        }
    }

    // === Per-player settings: getters ===
    private int getStrikes(Player p) {
        return strikesSetting.getOrDefault(p.getUniqueId(), STRIKES_DEFAULT);
    }
    private boolean getBlockDamage(Player p) {
        return blockDamageSetting.getOrDefault(p.getUniqueId(), BLOCK_DAMAGE_DEFAULT);
    }
    private float getExplosionPower(Player p) {
        return explosionPowerSetting.getOrDefault(p.getUniqueId(), EXPLOSION_POWER_DEFAULT);
    }

    // === GUI for per-player rifle settings ===
    private static final String GUI_TITLE = ChatColor.DARK_AQUA + "Orbital Rifle Controls";

    private void openRifleGui(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, GUI_TITLE);

        // Slot layout:
        // [ Strikes - ] [ Strikes value ] [ Strikes + ]
        // [ Explosion - ] [ Explosion value ] [ Explosion + ]
        // [ BlockDamage toggle ]

        // Helper items
        ItemStack minus = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta m1 = minus.getItemMeta();
        if (m1 != null) { m1.setDisplayName(ChatColor.RED + "-" + ChatColor.GRAY + " Decrease"); minus.setItemMeta(m1); }

        ItemStack plus = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta m2 = plus.getItemMeta();
        if (m2 != null) { m2.setDisplayName(ChatColor.GREEN + "+" + ChatColor.GRAY + " Increase"); plus.setItemMeta(m2); }

        // Strikes value item
        ItemStack strikesItem = new ItemStack(Material.CROSSBOW);
        ItemMeta sMeta = strikesItem.getItemMeta();
        if (sMeta != null) {
            sMeta.setDisplayName(ChatColor.AQUA + "Strikes: " + getStrikes(p));
            strikesItem.setItemMeta(sMeta);
        }

        // Explosion power value
        ItemStack powerItem = new ItemStack(Material.GUNPOWDER);
        ItemMeta pMeta = powerItem.getItemMeta();
        if (pMeta != null) {
            pMeta.setDisplayName(ChatColor.AQUA + "Explosion Power: " + getExplosionPower(p));
            powerItem.setItemMeta(pMeta);
        }

        // Block damage toggle
        boolean bd = getBlockDamage(p);
        ItemStack bdItem = new ItemStack(bd ? Material.TNT : Material.BARRIER);
        ItemMeta bdMeta = bdItem.getItemMeta();
        if (bdMeta != null) {
            bdMeta.setDisplayName(ChatColor.GOLD + "Block Damage: " + (bd ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            bdItem.setItemMeta(bdMeta);
        }

        // Place items
        inv.setItem(10, minus);
        inv.setItem(11, strikesItem);
        inv.setItem(12, plus);

        inv.setItem(13, bdItem);

        inv.setItem(14, minus);
        inv.setItem(15, powerItem);
        inv.setItem(16, plus);

        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView() == null || e.getView().getTitle() == null) return;
        if (!GUI_TITLE.equals(e.getView().getTitle())) return;

        e.setCancelled(true);
        if (e.getCurrentItem() == null) return;
        Material type = e.getCurrentItem().getType();

        int slot = e.getRawSlot();
        UUID id = p.getUniqueId();

        // Strikes - / value / +  in slots 10,11,12
        if (slot == 10 && type == Material.RED_STAINED_GLASS_PANE) {
            int v = Math.max(1, getStrikes(p) - 1);
            strikesSetting.put(id, v);
            openRifleGui(p);
            return;
        }
        if (slot == 12 && type == Material.LIME_STAINED_GLASS_PANE) {
            int v = Math.min(24, getStrikes(p) + 1);
            strikesSetting.put(id, v);
            openRifleGui(p);
            return;
        }

        // Block damage toggle in slot 13
        if (slot == 13 && (type == Material.TNT || type == Material.BARRIER)) {
            boolean newVal = !getBlockDamage(p);
            blockDamageSetting.put(id, newVal);
            openRifleGui(p);
            return;
        }

        // Explosion power - / value / + in slots 14,15,16
        if (slot == 14 && type == Material.RED_STAINED_GLASS_PANE) {
            float v = Math.max(0.0f, getExplosionPower(p) - 0.5f);
            explosionPowerSetting.put(id, v);
            openRifleGui(p);
            return;
        }
        if (slot == 16 && type == Material.LIME_STAINED_GLASS_PANE) {
            float v = Math.min(10.0f, getExplosionPower(p) + 0.5f);
            explosionPowerSetting.put(id, v);
            openRifleGui(p);
            return;
        }
    }
}