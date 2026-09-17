package com.ultikits.plugins.backup.service;

import com.ultikits.plugins.backup.UltiBackup;
import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Proves {@link BackupService} observes an in-place reload of the {@link BackupConfig} bean it was
 * injected with, which is what {@code /ul reload UltiBackup} does on UltiTools 6.3.0
 * ({@code ConfigManager#reloadConfigs} calls {@code init(plugin)} again on the same instance).
 * Backs the {@code ultibackup.lifecycle.reload} checklist row (UltiKits/UltiBackup#14).
 */
@DisplayName("BackupService observes an in-place BackupConfig reload (UltiKits/UltiBackup#14)")
class BackupConfigReloadTest {

    @TempDir
    Path moduleFolder;

    @TempDir
    Path dataFolder;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    @Test
    @DisplayName("lowering max_backups_per_player and re-initialising the same config prunes on the next backup")
    @SuppressWarnings("unchecked")
    void inPlaceReloadOfMaxBackupsPerPlayerIsObserved() throws Exception {
        File configFile = moduleFolder.resolve("config").resolve("backup.yml").toFile();
        assertThat(configFile.getParentFile().mkdirs()).isTrue();
        write(configFile, "max_backups_per_player: 10\n");

        UltiToolsPlugin plugin = mock(UltiBackup.class, CALLS_REAL_METHODS);
        setResourceFolderPath(plugin, moduleFolder.toString());

        BackupConfig config = new BackupConfig("config/backup.yml");
        config.init(plugin);
        assertThat(config.getMaxBackupsPerPlayer()).isEqualTo(10);

        UUID playerUuid = UUID.randomUUID();
        List<BackupMetadata> existing = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            BackupMetadata metadata = BackupMetadata.builder()
                    .playerUuid(playerUuid.toString())
                    .backupTime(1000L + i)
                    .build();
            metadata.setId("backup-" + i);
            existing.add(metadata);
        }
        DataOperator<BackupMetadata> dataOperator = mock(DataOperator.class);
        Query<BackupMetadata> query = mock(Query.class);
        when(dataOperator.query()).thenReturn(query);
        when(query.where("player_uuid")).thenReturn(query);
        when(query.eq(anyString())).thenReturn(query);
        when(query.list()).thenAnswer(inv -> new ArrayList<>(existing));

        Plugin bukkitPlugin = mock(Plugin.class);
        when(bukkitPlugin.getDataFolder()).thenReturn(dataFolder.toFile());

        BackupService service = new BackupService();
        UltiBackupTestHelper.setField(service, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(service, "config", config);
        UltiBackupTestHelper.setField(service, "dataOperator", dataOperator);
        UltiBackupTestHelper.setField(service, "bukkitPlugin", bukkitPlugin);
        Player player = UltiBackupTestHelper.createMockPlayer("Tester3", playerUuid);

        assertThat(service.createBackup(player, "MANUAL")).isNotNull();
        verify(dataOperator, never()).delById(anyString());

        write(configFile, "max_backups_per_player: 2\n");
        config.init(plugin);

        assertThat(service.createBackup(player, "MANUAL")).isNotNull();
        verify(dataOperator).delById("backup-1");
        verify(dataOperator).delById("backup-0");
        verify(dataOperator, times(2)).delById(anyString());
    }

    private static void write(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // points the module's config folder at a temp directory
    private static void setResourceFolderPath(UltiToolsPlugin plugin, String path) throws Exception {
        Field field = UltiToolsPlugin.class.getDeclaredField("resourceFolderPath");
        field.setAccessible(true);
        field.set(plugin, path);
    }
}
