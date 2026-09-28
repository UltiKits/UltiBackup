package com.ultikits.plugins.backup.commands;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.annotations.command.RunAsync;
import com.ultikits.ultitools.interfaces.DataOperator;
import com.ultikits.ultitools.interfaces.Query;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.scheduler.BukkitSchedulerMock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three backup commands read a player's inventory, armour, off-hand and ender chest on the
 * server's main thread, and write the file and the database row off it (UltiKits/UltiBackup#13).
 * <p>
 * Each path runs through a real {@link BackupService} on the live test server's scheduler. The
 * player's inventory and ender chest, and the database insert, record whether they were called on
 * the main thread. The command is invoked on the main thread, as the framework invokes a command
 * that is not {@code @RunAsync}; a command that is {@code @RunAsync} would be invoked off it, which
 * is why the annotation is checked too.
 */
@DisplayName("Backup commands snapshot on the main thread and write off it (UltiKits/UltiBackup#13)")
class BackupCommandThreadingTest {

    @TempDir
    Path tempDir;

    private BackupService service;
    private BackupCommand command;
    private Player player;
    private final List<Boolean> readsOnMainThread = new CopyOnWriteArrayList<>();
    private final List<Boolean> writesOnMainThread = new CopyOnWriteArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        service = new BackupService();
        DataOperator<BackupMetadata> dataOperator = mock(DataOperator.class);
        Query<BackupMetadata> query = mock(Query.class);
        when(dataOperator.query()).thenReturn(query);
        when(query.where(anyString())).thenReturn(query);
        when(query.eq(any())).thenReturn(query);
        when(query.list()).thenReturn(new ArrayList<>());
        Mockito.doAnswer(invocation -> {
            writesOnMainThread.add(Bukkit.isPrimaryThread());
            return null;
        }).when(dataOperator).insert(any(BackupMetadata.class));

        org.bukkit.plugin.Plugin bukkitPlugin = MockBukkit.createMockPlugin();
        UltiBackupTestHelper.setField(service, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(service, "config", UltiBackupTestHelper.createDefaultConfig());
        UltiBackupTestHelper.setField(service, "dataOperator", dataOperator);
        UltiBackupTestHelper.setField(service, "bukkitPlugin", bukkitPlugin);
        UltiBackupTestHelper.setField(service, "backupsDirectory", new File(tempDir.toFile(), "backups"));

        command = new BackupCommand();
        UltiBackupTestHelper.setField(command, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(command, "backupService", service);

        player = UltiBackupTestHelper.createMockPlayer("TestPlayer", UUID.randomUUID());
        PlayerInventory inventory = player.getInventory();
        when(inventory.getStorageContents()).thenAnswer(invocation -> {
            readsOnMainThread.add(Bukkit.isPrimaryThread());
            return new ItemStack[36];
        });
        when(inventory.getArmorContents()).thenAnswer(invocation -> {
            readsOnMainThread.add(Bukkit.isPrimaryThread());
            return new ItemStack[4];
        });
        Inventory enderChest = player.getEnderChest();
        when(enderChest.getContents()).thenAnswer(invocation -> {
            readsOnMainThread.add(Bukkit.isPrimaryThread());
            return new ItemStack[27];
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    private void finishScheduledWork() {
        BukkitSchedulerMock scheduler = MockBukkit.getMock().getScheduler();
        scheduler.waitAsyncTasksFinished();
        scheduler.performOneTick();
    }

    private void assertSnapshotOnMainThreadAndWriteOffIt() {
        assertThat(readsOnMainThread).as("inventory and ender chest reads").isNotEmpty().containsOnly(true);
        assertThat(writesOnMainThread).as("database writes").isNotEmpty().containsOnly(false);
    }

    @Test
    @DisplayName("none of the three handlers is @RunAsync, which would read the inventory off the main thread")
    void noHandlerIsRunAsync() throws Exception {
        for (Method method : new Method[] {
                BackupCommand.class.getMethod("createBackup", Player.class),
                BackupCommand.class.getMethod("saveAllPlayers", Player.class),
                BackupCommand.class.getMethod("adminCreateBackup", Player.class, String.class)}) {
            assertThat(method.getAnnotation(RunAsync.class)).as(method.getName()).isNull();
        }
    }

    @Test
    @DisplayName("/backup create")
    void create() {
        command.createBackup(player);
        finishScheduledWork();

        assertSnapshotOnMainThreadAndWriteOffIt();
        verify(player).sendMessage("backup.message.created");
    }

    @Test
    @DisplayName("/backup saveall")
    void saveAll() {
        Player admin = UltiBackupTestHelper.createMockPlayer("Admin", UUID.randomUUID());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(invocation -> Collections.singletonList(player));
            command.saveAllPlayers(admin);
            finishScheduledWork();
        }

        assertSnapshotOnMainThreadAndWriteOffIt();
        verify(admin).sendMessage("backup.message.saveall_complete");
    }

    @Test
    @DisplayName("/backup admin create <player>")
    void adminCreate() {
        Player admin = UltiBackupTestHelper.createMockPlayer("Admin", UUID.randomUUID());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
            bukkit.when(() -> Bukkit.getPlayerExact("TestPlayer")).thenReturn(player);
            command.adminCreateBackup(admin, "TestPlayer");
            finishScheduledWork();
        }

        assertSnapshotOnMainThreadAndWriteOffIt();
        verify(admin).sendMessage("backup.message.admin_created");
    }
}
