package com.ultikits.plugins.backup;

import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Test helper for mocking UltiTools framework dependencies.
 * <p>
 * Since the singleton pattern has been removed, this helper creates mock
 * UltiToolsPlugin instances for injection into services and commands.
 * <p>
 * This class is also the module's single centralized test-time live-server bootstrap
 * (Phase 14's reopen guard target): {@link #setUp()} installs a live {@code MockBukkit}
 * server before any mock wiring happens, and {@link #tearDown()} tears it back down.
 * Every test class in this module that needs a live Bukkit server -- including
 * {@link UltiBackupRegistrySentinelTest} -- routes through these two methods rather than
 * calling {@link MockBukkit#mock()}/{@link MockBukkit#unmock()} itself, so that removing or
 * breaking the bootstrap here is visible to every caller, not just the ones that happen to
 * exercise GUI code.
 * <p>
 * Call {@link #setUp()} in {@code @BeforeEach} and {@link #tearDown()} in {@code @AfterEach}.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
public final class UltiBackupTestHelper {

    private UltiBackupTestHelper() {}

    private static UltiBackup mockPlugin;
    private static PluginLogger mockLogger;

    /**
     * Set up UltiBackup mock. Must be called before each test.
     */
    @SuppressWarnings("unchecked")
    public static void setUp() throws Exception {
        // Centralized live-server bootstrap -- see class javadoc. Must run before any Mockito
        // wiring below, since production GUI/entity code under test constructs real,
        // registry-backed Bukkit objects that need a live server to resolve.
        MockBukkitSupport.ensureCleanState();
        MockBukkit.mock();

        // Mock UltiBackup (abstract UltiToolsPlugin — mockable)
        mockPlugin = mock(UltiBackup.class);

        // Mock logger
        mockLogger = mock(PluginLogger.class);
        lenient().when(mockPlugin.getLogger()).thenReturn(mockLogger);

        // Mock i18n to return the key as-is
        lenient().when(mockPlugin.i18n(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Mock getDataOperator
        lenient().when(mockPlugin.getDataOperator(any()))
                .thenReturn(mock(DataOperator.class));
    }

    /**
     * Clean up state.
     */
    public static void tearDown() throws Exception {
        mockPlugin = null;
        mockLogger = null;

        // Centralized live-server teardown -- see class javadoc.
        MockBukkitSupport.safeUnmock();
    }

    public static UltiBackup getMockPlugin() {
        return mockPlugin;
    }

    public static PluginLogger getMockLogger() {
        return mockLogger;
    }

    /**
     * Create a default BackupConfig mock with all features enabled.
     */
    public static BackupConfig createDefaultConfig() {
        BackupConfig config = mock(BackupConfig.class);
        lenient().when(config.isAutoBackupEnabled()).thenReturn(false);
        lenient().when(config.getAutoBackupInterval()).thenReturn(30);
        lenient().when(config.isBackupOnDeath()).thenReturn(true);
        lenient().when(config.isBackupOnQuit()).thenReturn(true);
        lenient().when(config.getMaxBackupsPerPlayer()).thenReturn(10);
        lenient().when(config.isBackupArmor()).thenReturn(true);
        lenient().when(config.isBackupEnderchest()).thenReturn(true);
        lenient().when(config.isBackupExp()).thenReturn(true);
        return config;
    }

    /**
     * Create a mock Player with basic properties.
     */
    public static Player createMockPlayer(String name, UUID uuid) {
        Player player = mock(Player.class);
        lenient().when(player.getName()).thenReturn(name);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
        lenient().when(player.getLevel()).thenReturn(30);
        lenient().when(player.getExp()).thenReturn(0.5f);
        lenient().when(player.hasPermission(anyString())).thenReturn(true);

        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        Location location = new Location(world, 100.5, 64.0, -200.5);
        lenient().when(player.getLocation()).thenReturn(location);
        lenient().when(player.getWorld()).thenReturn(world);

        PlayerInventory inventory = mock(PlayerInventory.class);
        lenient().when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
        lenient().when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        lenient().when(inventory.getItemInOffHand()).thenReturn(null);
        lenient().when(player.getInventory()).thenReturn(inventory);

        Inventory enderChest = mock(Inventory.class);
        lenient().when(enderChest.getContents()).thenReturn(new ItemStack[27]);
        lenient().when(player.getEnderChest()).thenReturn(enderChest);

        return player;
    }

    /**
     * Create a sample BackupMetadata (no file I/O involved).
     */
    public static BackupMetadata createSampleMetadata(UUID playerUuid, String playerName) {
        long now = System.currentTimeMillis();
        BackupMetadata metadata = BackupMetadata.builder()
                .playerUuid(playerUuid.toString())
                .playerName(playerName)
                .backupTime(now)
                .backupReason("MANUAL")
                .worldName("world")
                .locationX(100.5)
                .locationY(64.0)
                .locationZ(-200.5)
                .expLevel(30)
                .build();
        metadata.setId("test-id-" + now);
        metadata.setFilePath(metadata.generateFilePath());
        return metadata;
    }

    // --- Reflection ---

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        Field field = null;
        while (clazz != null) {
            try {
                field = clazz.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true); // NOPMD - intentional reflection for test mock injection
        field.set(target, value);
    }
}
