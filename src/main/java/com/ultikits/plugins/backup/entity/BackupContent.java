package com.ultikits.plugins.backup.entity;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Backup content POJO (cold data).
 * Contains serialized inventory data, stored in YAML files on disk.
 * <p>
 * 备份内容 POJO（冷数据）。
 * 包含序列化的背包数据，存储在磁盘上的 YAML 文件中。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BackupContent {
    
    /**
     * File header warning message.
     */
    public static final String FILE_HEADER = 
        "# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
        "# DO NOT MODIFY THIS FILE! 请勿修改此文件！\n" +
        "# Any modification will cause checksum verification failure\n" +
        "# 任何修改都会导致校验和验证失败，备份将无法恢复\n" +
        "# !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n" +
        "# Checksum: %s\n" +
        "\n";
    
    /**
     * Serialized inventory contents (YAML format).
     */
    private String inventoryContents;
    
    /**
     * Serialized armor contents (YAML format).
     */
    private String armorContents;
    
    /**
     * Serialized offhand item (YAML format).
     */
    private String offhandItem;
    
    /**
     * Serialized ender chest contents (YAML format).
     */
    private String enderchestContents;
    
    /**
     * Experience level.
     */
    private int expLevel;
    
    /**
     * Experience progress (0.0 - 1.0).
     */
    private float expProgress;
    
    /**
     * Create backup content from player.
     * <p>
     * 从玩家创建备份内容。
     *
     * @param player the player
     * @param backupArmor whether to backup armor
     * @param backupEnderchest whether to backup ender chest
     * @param backupExp whether to backup experience
     * @return the backup content
     */
    public static BackupContent fromPlayer(Player player, boolean backupArmor, 
            boolean backupEnderchest, boolean backupExp) {
        BackupContentBuilder builder = BackupContent.builder();
        
        // Serialize inventory
        builder.inventoryContents(serializeItems(player.getInventory().getStorageContents()));
        
        // Serialize armor
        if (backupArmor) {
            builder.armorContents(serializeItems(player.getInventory().getArmorContents()));
            builder.offhandItem(serializeItem(player.getInventory().getItemInOffHand()));
        }
        
        // Serialize ender chest
        if (backupEnderchest) {
            builder.enderchestContents(serializeItems(player.getEnderChest().getContents()));
        }
        
        // Experience
        if (backupExp) {
            builder.expLevel(player.getLevel());
            builder.expProgress(player.getExp());
        }
        
        return builder.build();
    }
    
    /**
     * Save content to file with SHA-256 checksum.
     * <p>
     * 将内容保存到文件（带 SHA-256 校验和）。
     *
     * @param file the file to save to
     * @return the SHA-256 checksum of the content
     * @throws IOException if save fails
     */
    public String saveToFile(File file) throws IOException {
        // Ensure parent directory exists
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        // Create YAML content
        YamlConfiguration yaml = new YamlConfiguration();
        // Always written, even for an empty inventory: loadFromFile refuses a file without it.
        yaml.set("inventory", inventoryContents == null ? "" : inventoryContents);
        yaml.set("armor", armorContents);
        yaml.set("offhand", offhandItem);
        yaml.set("enderchest", enderchestContents);
        yaml.set("expLevel", expLevel);
        yaml.set("expProgress", expProgress);

        String yamlContent = yaml.saveToString();

        // Calculate SHA-256 checksum
        String checksum = calculateChecksum(yamlContent);
        
        // Write file with header
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(String.format(FILE_HEADER, checksum));
            writer.write(yamlContent);
        }
        
        return checksum;
    }
    
    /**
     * Load content from file.
     * <p>
     * 从文件加载内容。
     *
     * @param file the file to load from
     * @return the backup content
     * @throws IOException if load fails
     */
    public static BackupContent loadFromFile(File file) throws IOException {
        // Not YamlConfiguration#loadConfiguration: for a body it cannot parse, that logs and returns
        // an EMPTY configuration, every part then reads as blank, and a restore would clear the
        // player's inventory and apply nothing (UltiKits/UltiBackup#21). A file this module wrote
        // always carries the "inventory" key (saveToFile sets it even for an empty inventory) and
        // ends with "expProgress" (the last key it writes, always set), so a file without either is
        // not a whole backup: saveToFile writes in one pass, and a crash or a full disk leaves a prefix.
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (InvalidConfigurationException e) {
            throw new IOException("Backup file is not valid YAML: " + file.getName(), e);
        }
        if (!yaml.contains("inventory")) {
            throw new IOException("Backup file has no inventory section: " + file.getName());
        }
        if (!yaml.contains("expProgress")) {
            throw new IOException("Backup file is incomplete (no expProgress, its last key): " + file.getName());
        }

        return BackupContent.builder()
            .inventoryContents(yaml.getString("inventory", ""))
            // No default: a missing armor or off-hand key means the backup was taken without armor
            // (backup_armor: false writes neither key), which a restore must tell apart from armor that
            // was empty when it was taken (written as '') (UltiKits/UltiBackup#25).
            .armorContents(yaml.getString("armor"))
            .offhandItem(yaml.getString("offhand"))
            .enderchestContents(yaml.getString("enderchest", ""))
            .expLevel(yaml.getInt("expLevel", 0))
            .expProgress((float) yaml.getDouble("expProgress", 0.0))
            .build();
    }
    
    /**
     * Verify file checksum.
     * <p>
     * 验证文件校验和。
     *
     * @param file the file to verify
     * @param expectedChecksum the expected checksum
     * @return true if checksum matches
     * @throws IOException if read fails
     */
    public static boolean verifyChecksum(File file, String expectedChecksum) throws IOException {
        if (!file.exists() || expectedChecksum == null) {
            return false;
        }
        
        // Read file and extract YAML content (skip header comments)
        StringBuilder contentBuilder = new StringBuilder();
        boolean inContent = false;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!inContent && !line.startsWith("#")) {
                    inContent = true;
                }
                if (inContent) {
                    contentBuilder.append(line).append("\n");
                }
            }
        }
        
        String actualChecksum = calculateChecksum(contentBuilder.toString().trim() + "\n");
        return expectedChecksum.equals(actualChecksum);
    }
    
    /**
     * Calculate SHA-256 checksum of string.
     * <p>
     * 计算字符串的 SHA-256 校验和。
     *
     * @param content the content to hash
     * @return the SHA-256 checksum (hex string)
     */
    public static String calculateChecksum(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
    
    /**
     * Restore content to player.
     * <p>
     * Every part the restore will apply is read back first. If any part whose stored text is not
     * blank cannot be read back, this throws {@link UnreadablePartException} before anything is
     * cleared or applied, so the player's inventory is left exactly as it was
     * (UltiKits/UltiBackup#21). A blank part is a part that was genuinely empty when it was backed
     * up, and is restored as empty, as before.
     * <p>
     * 将内容恢复到玩家。先读取将要恢复的每一部分；任一非空部分无法读取时，在清空或写入任何东西之前抛出
     * {@link UnreadablePartException}，玩家背包保持原样。
     *
     * @param player the player
     * @param restoreArmor whether to restore armor
     * @param restoreEnderchest whether to restore ender chest
     * @param restoreExp whether to restore experience
     * @throws UnreadablePartException if a part that is not blank cannot be read back; nothing
     *                                 has been changed on the player
     */
    public void restoreToPlayer(Player player, boolean restoreArmor, 
            boolean restoreEnderchest, boolean restoreExp) {
        // Read everything first. Nothing below this block may run unless every part read back.
        ItemStack[] contents = readItems(inventoryContents, PART_INVENTORY);
        ItemStack[] armor = null;
        ItemStack offhand = null;
        if (restoreArmor && armorContents != null) {
            armor = readItems(armorContents, PART_ARMOR);
            offhand = readItem(offhandItem, PART_OFFHAND);
        }
        ItemStack[] enderChest = null;
        if (restoreEnderchest && enderchestContents != null) {
            enderChest = readItems(enderchestContents, PART_ENDERCHEST);
        }

        // Clear what the restore replaces. PlayerInventory#clear() empties armor and off-hand too, so
        // unless this restore applies an armor part -- armor restored AND the backup has one -- only the
        // storage slots are cleared: otherwise the armor and off-hand the player is wearing would be
        // destroyed and nothing put back (UltiKits/UltiBackup#25).
        if (restoreArmor && armorContents != null) {
            player.getInventory().clear();
        } else {
            player.getInventory().setStorageContents(new ItemStack[player.getInventory().getStorageContents().length]);
        }
        
        // Restore inventory contents
        if (contents != null) {
            for (int i = 0; i < Math.min(contents.length, 36); i++) {
                if (contents[i] != null) {
                    player.getInventory().setItem(i, contents[i]);
                }
            }
        }
        
        // Restore armor
        if (armor != null) {
            player.getInventory().setArmorContents(armor);
        }
        if (offhand != null) {
            player.getInventory().setItemInOffHand(offhand);
        }
        
        // Restore ender chest
        if (enderChest != null) {
            player.getEnderChest().setContents(enderChest);
        }
        
        // Restore exp
        if (restoreExp) {
            player.setLevel(expLevel);
            player.setExp(expProgress);
        }
    }
    
    /**
     * The first part, in the order inventory, armor, off-hand, ender chest, whose stored text is
     * not blank but cannot be read back, or {@code null} when every part reads back.
     * <p>
     * 第一个非空但无法读取的部分；全部可读时返回 {@code null}。
     *
     * @return the failure for the first unreadable part, or {@code null}
     */
    public UnreadablePartException findUnreadablePart() {
        try {
            readItems(inventoryContents, PART_INVENTORY);
            readItems(armorContents, PART_ARMOR);
            readItem(offhandItem, PART_OFFHAND);
            readItems(enderchestContents, PART_ENDERCHEST);
            return null;
        } catch (UnreadablePartException e) {
            return e;
        }
    }

    /**
     * Get deserialized inventory items.
     * <p>
     * 获取反序列化的背包物品。
     *
     * @return the inventory items
     */
    public ItemStack[] getInventoryItems() {
        return deserializeItems(inventoryContents, PART_INVENTORY);
    }
    
    /**
     * Get deserialized armor items.
     * <p>
     * 获取反序列化的护甲物品。
     *
     * @return the armor items
     */
    public ItemStack[] getArmorItems() {
        return deserializeItems(armorContents, PART_ARMOR);
    }
    
    /**
     * Get deserialized offhand item.
     * <p>
     * 获取反序列化的副手物品。
     *
     * @return the offhand item
     */
    public ItemStack getOffhandItemStack() {
        return deserializeItem(offhandItem);
    }
    
    /**
     * Get deserialized ender chest items.
     * <p>
     * 获取反序列化的末影箱物品。
     *
     * @return the ender chest items
     */
    public ItemStack[] getEnderchestItems() {
        return deserializeItems(enderchestContents, PART_ENDERCHEST);
    }
    
    // ============ Serialization Utilities ============
    
    /**
     * Serialize items to YAML string.
     */
    private static String serializeItems(ItemStack[] items) {
        if (items == null) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < items.length; i++) {
            if (items[i] != null) {
                yaml.set("items." + i, items[i]);
            }
        }
        return yaml.saveToString();
    }
    
    /**
     * Serialize single item to YAML string.
     */
    private static String serializeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return "";
        
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("item", item);
        return yaml.saveToString();
    }
    
    /**
     * Deserialize items from YAML string; {@code null} when blank or unreadable.
     */
    private static ItemStack[] deserializeItems(String data, String part) {
        try {
            return readItems(data, part);
        } catch (UnreadablePartException e) {
            return null;
        }
    }

    /**
     * Deserialize single item from YAML string; {@code null} when blank or unreadable.
     */
    private static ItemStack deserializeItem(String data) {
        try {
            return readItem(data, PART_OFFHAND);
        } catch (UnreadablePartException e) {
            return null;
        }
    }

    /**
     * Read items back from their stored text.
     *
     * @return {@code null} for blank text (a part that was empty when it was backed up)
     * @throws UnreadablePartException if the text is not blank and does not read back as the
     *                                 items section {@link #serializeItems} writes
     */
    private static ItemStack[] readItems(String data, String part) {
        int capacity = capacityOf(part);
        if (data == null || data.isEmpty()) {
            return null;
        }

        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);

            // serializeItems writes an empty part as blank text, so an items section with no entries is
            // damaged data, not an empty part.
            if (!yaml.isConfigurationSection("items")
                    || yaml.getConfigurationSection("items").getKeys(false).isEmpty()) {
                throw new UnreadablePartException(part, null);
            }

            int maxSlot = 0;
            for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
                int slot = Integer.parseInt(key);
                // A slot outside the inventory the part is applied to cannot have been written by
                // fromPlayer, and applying it would throw after the inventory was already cleared.
                if (slot < 0 || slot >= capacity) {
                    throw new UnreadablePartException(part, null);
                }
                maxSlot = Math.max(maxSlot, slot);
            }

            ItemStack[] result = new ItemStack[maxSlot + 1];
            for (String key : yaml.getConfigurationSection("items").getKeys(false)) {
                int slot = Integer.parseInt(key);
                ItemStack item = yaml.getItemStack("items." + key);
                // serializeItems writes only non-empty slots, so an entry that reads back as
                // nothing, or as air, is an item this server could not rebuild.
                if (item == null || item.getType().isAir()) {
                    throw new UnreadablePartException(part, null);
                }
                result[slot] = item;
            }
            return result;
        } catch (UnreadablePartException e) {
            throw e;
        } catch (Exception e) {
            throw new UnreadablePartException(part, e);
        }
    }

    /**
     * Read one item back from its stored text.
     *
     * @return {@code null} for blank text (no item was held when it was backed up)
     * @throws UnreadablePartException if the text is not blank and does not read back as an item
     */
    private static ItemStack readItem(String data, String part) {
        if (data == null || data.isEmpty()) {
            return null;
        }

        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(data);
            ItemStack item = yaml.getItemStack("item");
            // serializeItem writes nothing for an empty hand, so air here is an unreadable item.
            if (item == null || item.getType().isAir()) {
                throw new UnreadablePartException(part, null);
            }
            return item;
        } catch (UnreadablePartException e) {
            throw e;
        } catch (Exception e) {
            throw new UnreadablePartException(part, e);
        }
    }

    /**
     * How many slots the inventory a part is applied to has: 36 storage slots, 4 armor slots, 27
     * ender chest slots (the sizes {@link #fromPlayer} reads them from).
     */
    private static int capacityOf(String part) {
        if (PART_ARMOR.equals(part)) {
            return 4;
        }
        if (PART_ENDERCHEST.equals(part)) {
            return 27;
        }
        return 36;
    }

    /** Stored key of the inventory part, as written in the backup file. */
    public static final String PART_INVENTORY = "inventory";
    /** Stored key of the armor part, as written in the backup file. */
    public static final String PART_ARMOR = "armor";
    /** Stored key of the off-hand part, as written in the backup file. */
    public static final String PART_OFFHAND = "offhand";
    /** Stored key of the ender chest part, as written in the backup file. */
    public static final String PART_ENDERCHEST = "enderchest";

    /**
     * A part of a backup whose stored text is not blank but cannot be read back into items.
     * <p>
     * 备份中非空但无法读取为物品的部分。
     */
    public static class UnreadablePartException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String part;

        /**
         * @param part  the part's stored key ({@link #PART_INVENTORY}, {@link #PART_ARMOR},
         *              {@link #PART_OFFHAND} or {@link #PART_ENDERCHEST})
         * @param cause what the reader threw, or {@code null} when the text parsed but held no item
         */
        public UnreadablePartException(String part, Throwable cause) {
            super("Backup part cannot be read: " + part, cause);
            this.part = part;
        }

        /**
         * @return the part's stored key, as written in the backup file
         */
        public String getPart() {
            return part;
        }
    }
}
