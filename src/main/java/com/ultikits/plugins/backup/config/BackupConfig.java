package com.ultikits.plugins.backup.config;

import com.ultikits.ultitools.abstracts.AbstractConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntity;
import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

import lombok.Getter;
import lombok.Setter;

/**
 * Backup configuration entity.
 * <p>
 * 备份配置实体。
 *
 * @author wisdomme
 * @version 2.0.0
 */
@Getter
@Setter
@ConfigEntity("config/backup.yml")
public class BackupConfig extends AbstractConfigEntity {

    @ConfigEntry(path = "auto_backup.enabled", comment = "Enable automatic backups")
    private boolean autoBackupEnabled = true;

    /**
     * Seconds between automatic backups, and before the first one after the module loads. Bound to
     * {@code BackupService#autoBackupIfEnabled} through the framework's config-bound {@code @Scheduled}:
     * 1 to 107374182 seconds, enforced by the framework's binding, not by {@code @Range} -- a module
     * {@code @Range} here would make an out-of-range {@code /ul reload} abort this module's reload instead
     * of keeping the running interval. An invalid value refuses the module at load and is ignored, with a
     * WARNING, at {@code /ul reload}. The default, 1800, is the 30 minutes the old minutes key defaulted to.
     * <p>
     * A file written before this key existed lacks it; the framework inserts it with its default at load and
     * changes nothing else in the file. This module never writes the file itself.
     * <p>
     * 自动备份间隔（秒），也是模块加载后第一次自动备份前的等待时间。由框架的配置绑定 {@code @Scheduled} 读取，
     * 取值范围 1 到 107374182 秒，由框架的绑定检查，而不是 {@code @Range}。默认 1800，即旧的分钟键默认的 30 分钟。
     */
    @ConfigEntry(path = "auto_backup.interval_seconds", comment = "Seconds between automatic backups (1 to 107374182; default 1800 = 30 minutes)")
    private int autoBackupIntervalSeconds = 1800;

    /**
     * Deprecated: the old automatic-backup interval, in minutes. Nothing reads it for scheduling any more:
     * binding it to the framework's seconds-valued {@code @Scheduled} would have turned an existing
     * {@code interval: 30} into a backup every 30 seconds. It stays declared, with its path, range and
     * default unchanged, so an operator's file keeps its meaning and is never rewritten; when it holds a
     * value other than its default, {@code BackupService} logs one warning at load naming
     * {@code auto_backup.interval_seconds}. Its value is never converted into the new key, on disk or in
     * memory.
     * <p>
     * The comment is literal, so the framework writes it only for a file that lacks this key and keeps an
     * existing file's comment line as it is. {@code previousComments} records the text earlier versions
     * shipped.
     * <p>
     * 已弃用：旧的自动备份间隔（分钟）。调度不再读取它；保留声明且不改写服务器文件，仅在其值不是默认值时于加载时警告一次。
     */
    @Range(min = 1, max = 1440)
    @ConfigEntry(path = "auto_backup.interval",
            comment = "Deprecated: no longer used. Set auto_backup.interval_seconds (seconds) instead",
            previousComments = {"Auto backup interval in minutes (1-1440)"})
    private int autoBackupInterval = 30;

    @ConfigEntry(path = "auto_backup.on_death", comment = "Backup inventory on player death")
    private boolean backupOnDeath = true;

    @ConfigEntry(path = "auto_backup.on_quit", comment = "Backup inventory when player quits")
    private boolean backupOnQuit = true;

    @Range(min = 1, max = 1000)
    @ConfigEntry(path = "max_backups_per_player", comment = "Maximum number of backups to keep per player (1-1000)")
    private int maxBackupsPerPlayer = 10;

    @ConfigEntry(path = "backup_armor", comment = "Include armor in backups")
    private boolean backupArmor = true;

    @ConfigEntry(path = "backup_enderchest", comment = "Include ender chest in backups")
    private boolean backupEnderchest = true;

    @ConfigEntry(path = "backup_exp", comment = "Include experience levels in backups")
    private boolean backupExp = true;

    public BackupConfig(String configFilePath) {
        super(configFilePath);
    }
}
