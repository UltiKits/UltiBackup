package com.ultikits.plugins.backup.service;

import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupContent;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.annotations.Autowired;
import com.ultikits.ultitools.annotations.Scheduled;
import com.ultikits.ultitools.annotations.Service;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.ultikits.ultitools.annotations.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Service for inventory backup operations.
 * Implements cold/hot data separation:
 * - Hot data (metadata) stored via DataOperator
 * - Cold data (backup content) stored in YAML files
 * <p>
 * 背包备份操作服务。
 * 实现冷热数据分离：
 * - 热数据（元数据）通过 DataOperator 存储
 * - 冷数据（备份内容）存储在 YAML 文件中
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Service
public class BackupService {

    @Autowired
    private UltiToolsPlugin plugin;

    @Autowired
    private BackupConfig config;

    private DataOperator<BackupMetadata> dataOperator;
    private File backupsDirectory;
    private Plugin bukkitPlugin;

    /** Minutes counted since the last automatic backup, by {@link #autoBackupTick()}. Main thread only. */
    private int minutesSinceAutoBackup = 0;

    /**
     * Initialize the service.
     * <p>
     * 初始化服务。
     */
    @PostConstruct
    public void init() {
        this.dataOperator = plugin.getDataOperator(BackupMetadata.class);
        this.bukkitPlugin = Bukkit.getPluginManager().getPlugin("UltiTools");

        // Ensure backups directory exists
        this.backupsDirectory = new File(bukkitPlugin.getDataFolder(), "backups");
        if (!backupsDirectory.exists()) {
            backupsDirectory.mkdirs();
        }
    }
    
    /**
     * Create a backup for a player, on the calling thread: {@link #snapshot} then {@link #write}.
     * The caller must be on the server's main thread, since the snapshot reads the player.
     * <p>
     * 为玩家创建备份（在调用线程上完成；调用方须在主线程）。
     *
     * @param player the player
     * @param reason the backup reason
     * @return the backup metadata
     */
    public BackupMetadata createBackup(Player player, String reason) {
        return write(snapshot(player, reason));
    }

    /**
     * Takes everything a backup needs from the player -- metadata, inventory, armour, off-hand,
     * ender chest and experience, already serialized to text -- so that writing it touches no
     * player, entity or world state. Must run on the server's main thread: reading an inventory
     * while the main thread changes it can give a torn copy (UltiKits/UltiBackup#13).
     * <p>
     * 在主线程上读取玩家的全部备份数据（已序列化为文本）；之后的写入不再访问玩家或世界状态。
     *
     * @param player the player
     * @param reason the backup reason
     * @return the snapshot, to be handed to {@link #write}
     */
    public PendingBackup snapshot(Player player, String reason) {
        BackupMetadata metadata = BackupMetadata.fromPlayer(player, reason);
        BackupContent content = BackupContent.fromPlayer(
            player,
            config.isBackupArmor(),
            config.isBackupEnderchest(),
            config.isBackupExp()
        );
        return new PendingBackup(metadata, content, player.getUniqueId(), player.getName());
    }

    /**
     * Writes a snapshot: the backup file and its checksum, the database row, and the pruning of old
     * backups. Reads nothing from the player, so it may run off the main thread.
     * <p>
     * 写入快照（文件、校验和、数据库、清理旧备份），不访问玩家，可在异步线程运行。
     *
     * @param pending the snapshot {@link #snapshot} took
     * @return the backup metadata, or {@code null} if the file could not be written
     */
    public BackupMetadata write(PendingBackup pending) {
        BackupMetadata metadata = pending.metadata;
        File backupFile = new File(bukkitPlugin.getDataFolder(), metadata.getFilePath());
        try {
            // Save cold data to file
            String checksum = pending.content.saveToFile(backupFile);
            metadata.setChecksum(checksum);
        } catch (IOException e) {
            plugin.getLogger().error(e, plugin.i18n("backup.log.create_failed")
                .replace("{PLAYER}", pending.playerName));
            return null;
        }

        try {
            // Save metadata to database
            dataOperator.insert(metadata);
        } catch (RuntimeException e) {
            // The file is already on disk; without its row nothing can list or prune it (UltiBackup#29)
            removeOrphanFile(backupFile, metadata);
            plugin.getLogger().error(e, plugin.i18n("backup.log.create_failed")
                .replace("{PLAYER}", pending.playerName));
            return null;
        }

        // Clean up old backups; the new backup is saved, so a failed prune must not report it as failed
        try {
            cleanupOldBackups(pending.playerUuid);
        } catch (RuntimeException e) {
            plugin.getLogger().warn(e, plugin.i18n("backup.log.cleanup_failed")
                .replace("{PLAYER}", pending.playerName));
        }

        plugin.getLogger().info(plugin.i18n("backup.log.created")
            .replace("{PLAYER}", pending.playerName)
            .replace("{FILE}", metadata.getFilePath()));

        return metadata;
    }

    /**
     * Deletes a backup file that has no database row, logging its path if it cannot be deleted.
     * <p>
     * 删除没有数据库记录的备份文件；删除失败时在日志中写出路径。
     */
    private void removeOrphanFile(File backupFile, BackupMetadata metadata) {
        if (backupFile.exists() && !backupFile.delete()) {
            warnFileNotDeleted(metadata, backupFile);
        }
    }

    private void warnFileNotDeleted(BackupMetadata metadata, File file) {
        plugin.getLogger().warn(plugin.i18n("backup.log.file_delete_failed")
            .replace("{ID}", metadata.getId() != null ? String.valueOf(metadata.getId()) : file.getName())
            .replace("{FILE}", file.getPath()));
    }

    /**
     * Backs a player up for a command: the snapshot is taken now, on the calling (main) thread;
     * the file and database writes run asynchronously; {@code whenWritten} then runs back on the main
     * thread with the result ({@code null} on failure). This replaces running the whole command
     * asynchronously, which read the inventory off the main thread (UltiKits/UltiBackup#13).
     * <p>
     * 命令用：立即在主线程取快照，异步写入，写完后回到主线程调用 {@code whenWritten}。
     *
     * @param player      the player, read now
     * @param reason      the backup reason
     * @param whenWritten receives the metadata, or {@code null}, on the main thread
     */
    public void createBackupAsync(Player player, String reason, Consumer<BackupMetadata> whenWritten) {
        PendingBackup pending = snapshot(player, reason);
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            BackupMetadata result = write(pending);
            Bukkit.getScheduler().runTask(bukkitPlugin, () -> whenWritten.accept(result));
        });
    }

    /**
     * {@link #saveAllOnlinePlayers()} for a command: every snapshot is taken now, on the calling
     * (main) thread; the writes run asynchronously; {@code whenWritten} then runs back on the main
     * thread with the number of backups written (UltiKits/UltiBackup#13).
     * <p>
     * 命令用：立即在主线程为所有在线玩家取快照，异步写入，写完后回到主线程返回写入数量。
     *
     * @param whenWritten receives the number of backups written, on the main thread
     */
    public void saveAllOnlinePlayersAsync(IntConsumer whenWritten) {
        List<PendingBackup> pending = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultibackup.auto")) {
                pending.add(snapshot(player, "ADMIN"));
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, () -> {
            int count = 0;
            for (PendingBackup backup : pending) {
                if (write(backup) != null) {
                    count++;
                }
            }
            int written = count;
            Bukkit.getScheduler().runTask(bukkitPlugin, () -> whenWritten.accept(written));
        });
    }

    /**
     * Everything one backup needs, read from the player on the main thread by {@link #snapshot}.
     * <p>
     * 一次备份所需的全部数据，由 {@link #snapshot} 在主线程读取。
     */
    public static final class PendingBackup {
        private final BackupMetadata metadata;
        private final BackupContent content;
        private final UUID playerUuid;
        private final String playerName;

        PendingBackup(BackupMetadata metadata, BackupContent content, UUID playerUuid, String playerName) {
            this.metadata = metadata;
            this.content = content;
            this.playerUuid = playerUuid;
            this.playerName = playerName;
        }
    }
    
    /**
     * Get all backups for a player.
     * <p>
     * 获取玩家的所有备份。
     *
     * @param playerUuid the player UUID
     * @return list of backups sorted by time descending
     */
    public List<BackupMetadata> getBackups(UUID playerUuid) {
        List<BackupMetadata> backups = dataOperator.query()
            .where("player_uuid").eq(playerUuid.toString())
            .list();

        // Sort by time descending
        backups.sort((a, b) -> Long.compare(b.getBackupTime(), a.getBackupTime()));

        return backups;
    }
    
    /**
     * Get a specific backup by ID.
     * <p>
     * 根据 ID 获取备份。
     *
     * @param id the backup ID
     * @return the backup metadata
     */
    public BackupMetadata getBackup(String id) {
        return dataOperator.getById(id);
    }
    
    /**
     * Verify backup checksum.
     * <p>
     * 验证备份校验和。
     *
     * @param metadata the backup metadata
     * @return true if checksum is valid
     */
    public boolean verifyChecksum(BackupMetadata metadata) {
        if (metadata == null || metadata.getFilePath() == null) {
            return false;
        }
        
        File backupFile = metadata.getBackupFile();
        if (backupFile == null || !backupFile.exists()) {
            return false;
        }
        
        try {
            return BackupContent.verifyChecksum(backupFile, metadata.getChecksum());
        } catch (IOException e) {
            plugin.getLogger().warn(e, plugin.i18n("backup.log.verify_failed")
                .replace("{ID}", String.valueOf(metadata.getId())));
            return false;
        }
    }
    
    /**
     * Load backup content from file.
     * <p>
     * 从文件加载备份内容。
     *
     * @param metadata the backup metadata
     * @return the backup content, or null if load fails
     */
    public BackupContent loadBackupContent(BackupMetadata metadata) {
        if (metadata == null || metadata.getFilePath() == null) {
            return null;
        }
        
        File backupFile = metadata.getBackupFile();
        if (backupFile == null || !backupFile.exists()) {
            return null;
        }
        
        try {
            return BackupContent.loadFromFile(backupFile);
        } catch (IOException e) {
            plugin.getLogger().warn(e, plugin.i18n("backup.log.load_failed")
                .replace("{ID}", String.valueOf(metadata.getId())));
            return null;
        }
    }
    
    /**
     * Restore a backup to a player (with checksum verification).
     * <p>
     * 将备份恢复到玩家（带校验和验证）。
     *
     * @param player the player
     * @param metadata the backup metadata
     * @return RestoreResult indicating success, failure, or checksum error
     */
    public RestoreResult restoreBackup(Player player, BackupMetadata metadata) {
        if (metadata == null) {
            return RestoreResult.NOT_FOUND;
        }
        
        // Verify checksum first
        if (!verifyChecksum(metadata)) {
            return RestoreResult.CHECKSUM_FAILED;
        }
        
        // Load and restore content
        return forceRestore(player, metadata);
    }
    
    /**
     * Force restore a backup without checksum verification.
     * Use this when user confirms to restore a corrupted backup.
     * <p>
     * 强制恢复备份（跳过校验和验证）。
     * 当用户确认恢复损坏的备份时使用。
     *
     * @param player the player
     * @param metadata the backup metadata
     * @return RestoreResult indicating success or failure
     */
    public RestoreResult forceRestore(Player player, BackupMetadata metadata) {
        if (metadata == null) {
            return RestoreResult.NOT_FOUND;
        }
        
        BackupContent content = loadBackupContent(metadata);
        if (content == null) {
            return RestoreResult.LOAD_FAILED;
        }
        
        try {
            content.restoreToPlayer(
                player,
                config.isBackupArmor(),
                config.isBackupEnderchest(),
                config.isBackupExp()
            );
            
            plugin.getLogger().info(plugin.i18n("backup.log.restored")
                .replace("{ID}", String.valueOf(metadata.getId()))
                .replace("{PLAYER}", player.getName()));
            
            return RestoreResult.SUCCESS;
        } catch (BackupContent.UnreadablePartException e) {
            // restoreToPlayer read every part before touching the player, so nothing was changed:
            // report failure, never success (UltiKits/UltiBackup#21).
            plugin.getLogger().warn(e, plugin.i18n("backup.log.restore_unreadable")
                .replace("{ID}", String.valueOf(metadata.getId()))
                .replace("{PLAYER}", player.getName())
                .replace("{PART}", e.getPart()));
            return RestoreResult.RESTORE_FAILED;
        } catch (Exception e) {
            plugin.getLogger().error(e, plugin.i18n("backup.log.restore_failed")
                .replace("{ID}", String.valueOf(metadata.getId()))
                .replace("{PLAYER}", player.getName()));
            return RestoreResult.RESTORE_FAILED;
        }
    }
    
    /**
     * Delete a backup.
     * <p>
     * 删除备份。
     *
     * @param metadata the backup metadata
     * @return true if its database row was deleted; false if it had no row (already deleted)
     */
    public boolean deleteBackup(BackupMetadata metadata) {
        if (metadata == null || metadata.getId() == null) {
            return false;
        }

        // A row that is already gone was not deleted by this call (UltiBackup#29)
        if (dataOperator.getById(metadata.getId()) == null) {
            return false;
        }

        // Row first: if this throws, the file is still there for the row that remains
        dataOperator.delById(metadata.getId());

        // Then the cold data file; a failure leaves a file without a row, so name it
        File file = metadata.getBackupFile();
        if (!metadata.deleteBackupFile() && file != null) {
            warnFileNotDeleted(metadata, file);
        }

        return true;
    }
    
    /**
     * Delete a backup by ID.
     * <p>
     * 根据 ID 删除备份。
     *
     * @param id the backup ID
     * @return true if deleted successfully
     */
    public boolean deleteBackup(String id) {
        BackupMetadata metadata = dataOperator.getById(id);
        return deleteBackup(metadata);
    }
    
    /**
     * Save all online players' backups.
     * <p>
     * 保存所有在线玩家的备份。
     *
     * @return the number of backups created
     */
    public int saveAllOnlinePlayers() {
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultibackup.auto")) {
                BackupMetadata result = createBackup(player, "ADMIN");
                if (result != null) {
                    count++;
                }
            }
        }
        return count;
    }
    
    /**
     * Counts minutes toward {@code auto_backup.interval} and runs {@link #autoBackupAll()} each time
     * the configured number of minutes has passed.
     * <p>
     * The interval is declared in minutes (1-1440). It used to be read by nothing: the backup ran on a
     * fixed 36000-tick schedule, every 30 minutes whatever the file said (UltiKits/UltiBackup#24).
     * The framework's config-bound {@code @Scheduled} reads its key in seconds, so binding this key
     * would have turned an existing {@code interval: 30} into 30 seconds; counting minutes here keeps
     * the key, its unit, its range and its default, and a value changed by {@code /ul reload} or the
     * panel applies at the next minute.
     * <p>
     * 每分钟计数一次，达到 {@code auto_backup.interval}（分钟）时执行自动备份；修改后的值在下一分钟生效。
     */
    @Scheduled(delay = 1200, period = 1200, async = false)
    public void autoBackupTick() {
        if (!config.isAutoBackupEnabled()) {
            return;
        }
        minutesSinceAutoBackup++;
        if (minutesSinceAutoBackup >= config.getAutoBackupInterval()) {
            minutesSinceAutoBackup = 0;
            autoBackupAll();
        }
    }

    /**
     * Auto backup all online players. Run by {@link #autoBackupTick()} every
     * {@code auto_backup.interval} minutes. Checks config.auto_backup.enabled before executing.
     * <p>
     * 自动备份所有在线玩家。由 {@link #autoBackupTick()} 每隔 {@code auto_backup.interval} 分钟调用。
     * 执行前检查 config.auto_backup.enabled。
     */
    public void autoBackupAll() {
        // Check if auto backup is enabled
        if (!config.isAutoBackupEnabled()) {
            return;
        }

        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("ultibackup.auto")) {
                BackupMetadata result = createBackup(player, "AUTO");
                if (result != null) {
                    count++;
                }
            }
        }
        if (count > 0) {
            plugin.getLogger().info(plugin.i18n("backup.log.auto_completed")
                .replace("{COUNT}", String.valueOf(count)));
        }
    }
    
    /**
     * Clean up old backups for a player.
     * <p>
     * 清理玩家的旧备份。
     *
     * @param playerUuid the player UUID
     */
    private void cleanupOldBackups(UUID playerUuid) {
        List<BackupMetadata> backups = getBackups(playerUuid);
        
        if (backups.size() > config.getMaxBackupsPerPlayer()) {
            // Remove oldest backups
            for (int i = config.getMaxBackupsPerPlayer(); i < backups.size(); i++) {
                deleteBackup(backups.get(i));
            }
        }
    }
    
    /**
     * Get the config.
     * <p>
     * 获取配置。
     *
     * @return the backup config
     */
    public BackupConfig getConfig() {
        return config;
    }
    
    /**
     * Get the backups directory.
     * <p>
     * 获取备份目录。
     *
     * @return the backups directory
     */
    public File getBackupsDirectory() {
        return backupsDirectory;
    }
    
    /**
     * Restore result enum.
     * <p>
     * 恢复结果枚举。
     */
    public enum RestoreResult {
        /** Restore successful */
        SUCCESS,
        /** Backup not found */
        NOT_FOUND,
        /** Checksum verification failed */
        CHECKSUM_FAILED,
        /** Failed to load backup content */
        LOAD_FAILED,
        /** Failed to restore to player */
        RESTORE_FAILED
    }
}
