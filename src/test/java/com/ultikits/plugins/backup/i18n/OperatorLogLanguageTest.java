package com.ultikits.plugins.backup.i18n;

import com.ultikits.plugins.backup.UltiBackup;
import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupContent;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.i18n.UltiBackupLanguageCatalogueTest.Catalogue;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every console line this module writes for the operator follows the framework's {@code language}.
 * <p>
 * Each test answers the module's {@code i18n} from the real shipped {@code zh} catalogue through
 * the framework's own {@link Language} and asserts the exact line logged. Before the sweep these
 * lines were English literals, so each test fails by showing the English line actually logged.
 */
@DisplayName("Operator log lines follow the language setting")
class OperatorLogLanguageTest {

    @TempDir
    Path tempDir;

    private UltiBackup plugin;
    private PluginLogger logger;
    private Map<String, String> zh;
    private BackupService service;
    private BackupConfig config;
    private DataOperator<BackupMetadata> dataOperator;
    private Player player;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        plugin = UltiBackupTestHelper.getMockPlugin();
        logger = UltiBackupTestHelper.getMockLogger();
        zh = null;
        for (Catalogue c : UltiBackupLanguageCatalogueTest.loadModuleCatalogues()) {
            if (c.code.equals("zh")) {
                zh = c.entries;
            }
        }
        Language language = new Language(zh);
        when(plugin.i18n(anyString())).thenAnswer(inv -> language.getLocalizedText(inv.getArgument(0)));

        config = UltiBackupTestHelper.createDefaultConfig();
        dataOperator = mock(DataOperator.class);
        service = new BackupService();
        UltiBackupTestHelper.setField(service, "plugin", plugin);
        UltiBackupTestHelper.setField(service, "config", config);
        UltiBackupTestHelper.setField(service, "dataOperator", dataOperator);
        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    /** The catalogue line with its placeholders filled, or a marker naming the missing key. */
    private String expected(String key, String... placeholderValuePairs) {
        String text = zh.get(key);
        if (text == null) {
            return "<lang/zh.yml has no " + key + ">";
        }
        for (int i = 0; i + 1 < placeholderValuePairs.length; i += 2) {
            text = text.replace(placeholderValuePairs[i], placeholderValuePairs[i + 1]);
        }
        return text;
    }

    private void useDataFolder(File folder) throws Exception {
        org.bukkit.plugin.Plugin bukkitPlugin = mock(org.bukkit.plugin.Plugin.class);
        when(bukkitPlugin.getDataFolder()).thenReturn(folder);
        UltiBackupTestHelper.setField(service, "bukkitPlugin", bukkitPlugin);
    }

    @Test
    @DisplayName("enable line")
    void enableLine() {
        when(plugin.registerSelf()).thenCallRealMethod();

        plugin.registerSelf();

        verify(logger).info(expected("backup.log.enabled"));
    }

    @Test
    @DisplayName("backup created")
    @SuppressWarnings("unchecked")
    void backupCreated() throws Exception {
        useDataFolder(tempDir.toFile());
        Query<BackupMetadata> query = mock(Query.class);
        when(dataOperator.query()).thenReturn(query);
        when(query.where("player_uuid")).thenReturn(query);
        when(query.eq(anyString())).thenReturn(query);
        when(query.list()).thenReturn(new ArrayList<>());

        BackupMetadata result = service.createBackup(player, "MANUAL");

        assertThat(result).isNotNull();
        verify(logger).info(expected("backup.log.created",
                "{PLAYER}", "TestPlayer", "{FILE}", result.getFilePath()));
    }

    @Test
    @DisplayName("backup creation failed")
    void backupCreationFailed() throws Exception {
        File notADirectory = tempDir.resolve("data-folder-is-a-file").toFile();
        Files.write(notADirectory.toPath(), new byte[0]);
        useDataFolder(notADirectory);

        assertThat(service.createBackup(player, "MANUAL")).isNull();

        verify(logger).error(any(IOException.class),
                org.mockito.ArgumentMatchers.eq(expected("backup.log.create_failed", "{PLAYER}", "TestPlayer")));
    }

    @Test
    @DisplayName("checksum could not be read")
    void checksumUnreadable() throws Exception {
        BackupMetadata metadata = spy(BackupMetadata.builder().filePath("x.yml").checksum("abc").build());
        metadata.setId("backup-1");
        doReturn(tempDir.toFile()).when(metadata).getBackupFile();

        assertThat(service.verifyChecksum(metadata)).isFalse();

        verify(logger).warn(any(IOException.class),
                org.mockito.ArgumentMatchers.eq(expected("backup.log.verify_failed", "{ID}", "backup-1")));
    }

    @Test
    @DisplayName("backup content could not be loaded")
    void contentUnloadable() throws Exception {
        File file = tempDir.resolve("content.yml").toFile();
        Files.write(file.toPath(), new byte[0]);
        BackupMetadata metadata = spy(BackupMetadata.builder().filePath("content.yml").build());
        metadata.setId("backup-2");
        doReturn(file).when(metadata).getBackupFile();

        try (MockedStatic<BackupContent> content = mockStatic(BackupContent.class, CALLS_REAL_METHODS)) {
            content.when(() -> BackupContent.loadFromFile(file)).thenThrow(new IOException("unreadable"));
            assertThat(service.loadBackupContent(metadata)).isNull();
        }

        verify(logger).warn(any(IOException.class),
                org.mockito.ArgumentMatchers.eq(expected("backup.log.load_failed", "{ID}", "backup-2")));
    }

    @Test
    @DisplayName("backup restored")
    void backupRestored() {
        BackupService spyService = spy(service);
        BackupMetadata metadata = BackupMetadata.builder().build();
        metadata.setId("backup-3");
        doReturn(BackupContent.builder().expLevel(1).build()).when(spyService).loadBackupContent(metadata);

        assertThat(spyService.forceRestore(player, metadata)).isEqualTo(BackupService.RestoreResult.SUCCESS);

        verify(logger).info(expected("backup.log.restored", "{ID}", "backup-3", "{PLAYER}", "TestPlayer"));
    }

    @Test
    @DisplayName("backup restore failed")
    void backupRestoreFailed() {
        BackupService spyService = spy(service);
        BackupMetadata metadata = BackupMetadata.builder().build();
        metadata.setId("backup-4");
        BackupContent content = mock(BackupContent.class);
        doThrow(new RuntimeException("fail")).when(content)
                .restoreToPlayer(any(), anyBoolean(), anyBoolean(), anyBoolean());
        doReturn(content).when(spyService).loadBackupContent(metadata);

        assertThat(spyService.forceRestore(player, metadata))
                .isEqualTo(BackupService.RestoreResult.RESTORE_FAILED);

        verify(logger).error(any(RuntimeException.class),
                org.mockito.ArgumentMatchers.eq(expected("backup.log.restore_failed",
                        "{ID}", "backup-4", "{PLAYER}", "TestPlayer")));
    }

    @Test
    @DisplayName("automatic backup completed")
    void autoBackupCompleted() {
        when(config.isAutoBackupEnabled()).thenReturn(true);
        BackupService spyService = spy(service);
        doReturn(BackupMetadata.builder().build()).when(spyService).createBackup(player, "AUTO");

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(Arrays.asList(player));
            spyService.autoBackupAll();
        }

        verify(logger).info(expected("backup.log.auto_completed", "{COUNT}", "1"));
    }

    @Test
    @DisplayName("control: the zh catalogue was loaded and the logger is the one the module uses")
    void control() {
        assertThat(zh).isNotEmpty().containsKey("backup.message.created");
        ArgumentCaptor<String> line = ArgumentCaptor.forClass(String.class);
        plugin.getLogger().info("probe");
        verify(logger).info(line.capture());
        assertThat(line.getValue()).isEqualTo("probe");
    }
}
