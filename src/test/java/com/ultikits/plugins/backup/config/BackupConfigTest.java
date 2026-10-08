package com.ultikits.plugins.backup.config;

import com.ultikits.ultitools.annotations.ConfigEntry;
import com.ultikits.ultitools.annotations.config.Range;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupConfig Tests")
class BackupConfigTest {

    @Nested
    @DisplayName("Default Values")
    class DefaultValues {

        @Test
        @DisplayName("Should have auto backup enabled by default")
        void autoBackupEnabled() {
            BackupConfig config = createRealConfig();
            assertThat(config.isAutoBackupEnabled()).isTrue();
        }

        @Test
        @DisplayName("Should have 30 minute default interval")
        void defaultInterval() {
            BackupConfig config = createRealConfig();
            assertThat(config.getAutoBackupInterval()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should have backup on death enabled by default")
        void backupOnDeath() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupOnDeath()).isTrue();
        }

        @Test
        @DisplayName("Should have backup on quit enabled by default")
        void backupOnQuit() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupOnQuit()).isTrue();
        }

        @Test
        @DisplayName("Should have max 10 backups per player by default")
        void maxBackups() {
            BackupConfig config = createRealConfig();
            assertThat(config.getMaxBackupsPerPlayer()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should have backup armor enabled by default")
        void backupArmor() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupArmor()).isTrue();
        }

        @Test
        @DisplayName("Should have backup enderchest enabled by default")
        void backupEnderchest() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupEnderchest()).isTrue();
        }

        @Test
        @DisplayName("Should have backup exp enabled by default")
        void backupExp() {
            BackupConfig config = createRealConfig();
            assertThat(config.isBackupExp()).isTrue();
        }
    }

    @Nested
    @DisplayName("Setters")
    class Setters {

        @Test
        @DisplayName("Should update auto backup enabled")
        void setAutoBackupEnabled() {
            BackupConfig config = createRealConfig();
            config.setAutoBackupEnabled(false);
            assertThat(config.isAutoBackupEnabled()).isFalse();
        }

        @Test
        @DisplayName("Should update auto backup interval")
        void setAutoBackupInterval() {
            BackupConfig config = createRealConfig();
            config.setAutoBackupInterval(60);
            assertThat(config.getAutoBackupInterval()).isEqualTo(60);
        }

        @Test
        @DisplayName("Should update backup on death")
        void setBackupOnDeath() {
            BackupConfig config = createRealConfig();
            config.setBackupOnDeath(false);
            assertThat(config.isBackupOnDeath()).isFalse();
        }

        @Test
        @DisplayName("Should update backup on quit")
        void setBackupOnQuit() {
            BackupConfig config = createRealConfig();
            config.setBackupOnQuit(false);
            assertThat(config.isBackupOnQuit()).isFalse();
        }

        @Test
        @DisplayName("Should update max backups per player")
        void setMaxBackups() {
            BackupConfig config = createRealConfig();
            config.setMaxBackupsPerPlayer(20);
            assertThat(config.getMaxBackupsPerPlayer()).isEqualTo(20);
        }

        @Test
        @DisplayName("Should update backup armor")
        void setBackupArmor() {
            BackupConfig config = createRealConfig();
            config.setBackupArmor(false);
            assertThat(config.isBackupArmor()).isFalse();
        }

        @Test
        @DisplayName("Should update backup enderchest")
        void setBackupEnderchest() {
            BackupConfig config = createRealConfig();
            config.setBackupEnderchest(false);
            assertThat(config.isBackupEnderchest()).isFalse();
        }

        @Test
        @DisplayName("Should update backup exp")
        void setBackupExp() {
            BackupConfig config = createRealConfig();
            config.setBackupExp(false);
            assertThat(config.isBackupExp()).isFalse();
        }
    }

    /**
     * The automatic-backup interval moved to a new seconds-valued key bound through the framework's
     * {@code @Scheduled}. The old minutes key stays declared, path and default unchanged, so an operator's file
     * keeps its meaning and is never rewritten; it no longer drives anything.
     * <p>
     * Fields are found by their {@code @ConfigEntry} path, so a missing field fails on an assertion.
     */
    @Nested
    @DisplayName("auto_backup.interval_seconds and the legacy minutes key")
    class IntervalKeys {

        private Field fieldAt(String path) {
            for (Field f : BackupConfig.class.getDeclaredFields()) {
                ConfigEntry entry = f.getAnnotation(ConfigEntry.class);
                if (entry != null && entry.path().equals(path)) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        }

        @Test
        @DisplayName("auto_backup.interval_seconds: an int defaulting to 1800 (the old 30 minutes), with no @Range")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void secondsKeyDeclared() throws Exception {
            Field f = fieldAt("auto_backup.interval_seconds");
            assertThat(f).as("a @ConfigEntry field at auto_backup.interval_seconds").isNotNull();
            assertThat(f.getType()).isEqualTo(int.class);
            // The binding's own range (1 to Integer.MAX_VALUE / 20 seconds) is the field's range: a module
            // @Range would make an out-of-range reload abort the module's reload instead of keeping the
            // running cadence
            assertThat(f.getAnnotation(Range.class)).isNull();
            assertThat(f.getInt(createRealConfig())).isEqualTo(1800);
        }

        @Test
        @DisplayName("auto_backup.interval: path, default 30 and range kept; the comment names the new key; the old comment is listed")
        @SuppressWarnings("PMD.AvoidAccessibilityAlteration")
        void legacyKeyKept() throws Exception {
            Field f = fieldAt("auto_backup.interval");
            assertThat(f).as("the legacy @ConfigEntry field at auto_backup.interval").isNotNull();
            assertThat(f.getInt(createRealConfig())).isEqualTo(30);
            ConfigEntry entry = f.getAnnotation(ConfigEntry.class);
            assertThat(entry.comment()).contains("auto_backup.interval_seconds");
            assertThat(entry.previousComments()).contains("Auto backup interval in minutes (1-1440)");
        }
    }

    /**
     * Create a real BackupConfig using a mock path to avoid AbstractConfigEntity I/O.
     * We use Mockito spy to bypass the superclass constructor's file loading.
     */
    private BackupConfig createRealConfig() {
        // Use mock to avoid AbstractConfigEntity file I/O, then set fields
        BackupConfig config = mock(BackupConfig.class, withSettings().useConstructor("config/backup.yml").defaultAnswer(CALLS_REAL_METHODS));
        return config;
    }
}
