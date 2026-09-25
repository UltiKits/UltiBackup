package com.ultikits.plugins.backup.i18n;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.commands.BackupCommand;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.gui.BackupGUI;
import com.ultikits.plugins.backup.i18n.UltiBackupLanguageCatalogueTest.Catalogue;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.entities.Language;
import com.ultikits.ultitools.utils.XVersionUtils;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UltiKits/UltiBackup#15: a backup's reason is shown through the {@code backup.reason.*} catalogue
 * entries in the server's language, not as the stored constant.
 * <p>
 * The plugin's {@code i18n} is answered by the framework's own {@link Language} over the module's
 * real shipped catalogue, so a key that is missing renders as the key, exactly as on a server.
 */
@DisplayName("#15: backup reasons display through the catalogue")
class BackupReasonDisplayTest {

    private UltiToolsPlugin plugin;
    private BackupService backupService;
    private Player player;
    private UUID playerUuid;
    private Locale originalLocale;

    @BeforeEach
    void setUp() throws Exception {
        originalLocale = Locale.getDefault();
        UltiBackupTestHelper.setUp();
        plugin = UltiBackupTestHelper.getMockPlugin();
        backupService = mock(BackupService.class);
        playerUuid = UUID.randomUUID();
        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", playerUuid);
    }

    @AfterEach
    void tearDown() throws Exception {
        Locale.setDefault(originalLocale);
        UltiBackupTestHelper.tearDown();
    }

    private Map<String, String> speak(String code) throws Exception {
        for (Catalogue c : UltiBackupLanguageCatalogueTest.loadModuleCatalogues()) {
            if (c.code.equals(code)) {
                Language language = new Language(c.entries);
                lenient().when(plugin.i18n(anyString()))
                        .thenAnswer(inv -> language.getLocalizedText(inv.getArgument(0)));
                return c.entries;
            }
        }
        throw new IllegalStateException("no catalogue for " + code);
    }

    private String listLineFor(String reason) throws Exception {
        BackupMetadata backup = BackupMetadata.builder()
                .playerUuid(playerUuid.toString()).playerName("TestPlayer")
                .backupTime(1000L).backupReason(reason).worldName("world").build();
        when(backupService.getBackups(playerUuid)).thenReturn(Collections.singletonList(backup));
        BackupCommand command = new BackupCommand();
        UltiBackupTestHelper.setField(command, "plugin", plugin);
        UltiBackupTestHelper.setField(command, "backupService", backupService);

        command.listBackups(player);

        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(player, atLeastOnce()).sendMessage(sent.capture());
        return sent.getAllValues().get(1);
    }

    @ParameterizedTest(name = "{0} in {1} shows {2}")
    @CsvSource({
            "DEATH,          en, backup.reason.death",
            "QUIT,           en, backup.reason.quit",
            "AUTO,           en, backup.reason.auto",
            "MANUAL,         en, backup.reason.manual",
            "ADMIN,          en, backup.reason.admin",
            "DEATH,          zh, backup.reason.death",
            "QUIT,           zh, backup.reason.quit",
            "AUTO,           zh, backup.reason.auto",
            "MANUAL,         zh, backup.reason.manual",
            "ADMIN,          zh, backup.reason.admin",
            "SOMETHING_ELSE, en, backup.reason.unknown",
            "SOMETHING_ELSE, zh, backup.reason.unknown",
            "death,          en, backup.reason.death"
    })
    @DisplayName("/backup list shows the catalogue label for the stored reason")
    void listShowsCatalogueLabel(String reason, String code, String key) throws Exception {
        Map<String, String> catalogue = speak(code);

        String line = listLineFor(reason);

        assertThat(catalogue).containsKey(key);
        assertThat(line).endsWith(" " + catalogue.get(key)).doesNotContain(reason);
    }

    @Test
    @DisplayName("a backup with no stored reason shows the unknown label")
    void nullReasonShowsUnknown() throws Exception {
        Map<String, String> en = speak("en");

        assertThat(listLineFor(null)).endsWith(" " + en.get("backup.reason.unknown"));
    }

    @Test
    @DisplayName("the mapping does not depend on the JVM's default locale (Turkish dotless i)")
    void mappingIsLocaleIndependent() throws Exception {
        Map<String, String> en = speak("en");
        Locale.setDefault(new Locale("tr", "TR"));

        assertThat(listLineFor("QUIT")).endsWith(" " + en.get("backup.reason.quit"));
    }

    @Test
    @DisplayName("the backup browser's lore shows the catalogue label")
    void guiLoreShowsCatalogueLabel() throws Exception {
        Map<String, String> en = speak("en");
        BackupMetadata backup = BackupMetadata.builder()
                .playerUuid(playerUuid.toString()).playerName("TestPlayer")
                .backupTime(1000L).backupReason("DEATH").worldName("world").build();
        when(backupService.getBackups(playerUuid)).thenReturn(Collections.singletonList(backup));
        Inventory inventory = mock(Inventory.class);

        // Same static set-up as BackupGUITest, for the same measured reasons recorded there; the
        // inventory is a mock so the item placed in slot 0 can be read back.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, org.mockito.Mockito.CALLS_REAL_METHODS);
             MockedStatic<XVersionUtils> xVersion = mockStatic(XVersionUtils.class)) {
            bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString()))
                    .thenReturn(inventory);
            xVersion.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                    .thenReturn(mock(ItemStack.class));

            new BackupGUI(plugin, backupService, player, playerUuid, "TestPlayer");
        }

        ArgumentCaptor<ItemStack> item = ArgumentCaptor.forClass(ItemStack.class);
        verify(inventory).setItem(eq(0), item.capture());
        List<String> lore = item.getValue().getItemMeta().getLore();
        String expected = en.get("backup.gui.reason").replace("{REASON}", en.get("backup.reason.death"));
        assertThat(lore).isNotNull().first().isEqualTo(expected);
    }
}
