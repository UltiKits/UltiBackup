package com.ultikits.plugins.backup.entity;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.config.BackupConfig;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.interfaces.DataOperator;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Restoring a backup whose items cannot be read
 * (<a href="https://github.com/UltiKits/UltiBackup/issues/21">UltiBackup#21</a>).
 *
 * <h2>The defect</h2>
 * {@code BackupContent#restoreToPlayer} cleared the inventory before it read anything, a part that
 * could not be read was logged and skipped, and the restore still returned normally, so
 * {@code BackupService#forceRestore} answered {@code SUCCESS}: the player's items were gone and the
 * success message was sent.
 *
 * <h2>What makes a vacuous pass impossible here</h2>
 * The player is a MockBukkit player with a real inventory, ender chest and experience, and every
 * assertion reads what that player holds afterwards. The backups are written by the module's own
 * {@code fromPlayer} + {@code saveToFile}, so a "valid" backup is exactly what production writes,
 * and the corrupted ones are that same text with one edit. Each refusal case is paired with a
 * control that restores the unedited backup and sees the items come back, so "nothing changed"
 * cannot come from a restore that never applies anything.
 */
@DisplayName("Restore of an unreadable backup changes nothing and reports failure (UltiBackup#21)")
class BackupRestoreSafetyTest {

    @TempDir
    Path tempDir;

    private PlayerMock player;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        player = MockBukkit.getMock().addPlayer("Restorer");
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    /** What the player holds when the backup is taken. */
    private void giveBackedUpState() {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItem(5, new ItemStack(Material.OAK_LOG, 16));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.getEnderChest().setItem(2, new ItemStack(Material.EMERALD, 7));
        player.setLevel(12);
    }

    /** What the player holds at restore time; must survive a refused restore untouched. */
    private void giveCurrentState() {
        player.getInventory().clear();
        player.getEnderChest().clear();
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_INGOT, 9));
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.TORCH, 4));
        player.getEnderChest().setItem(0, new ItemStack(Material.APPLE, 5));
        player.setLevel(30);
    }

    private void assertCurrentStateUnchanged() {
        assertThat(player.getInventory().getItem(1))
                .as("the item the player holds now is still there")
                .isEqualTo(new ItemStack(Material.GOLD_INGOT, 9));
        assertThat(player.getInventory().getItem(0)).as("nothing from the backup was applied").isNull();
        assertThat(player.getInventory().getChestplate()).as("armor untouched")
                .isEqualTo(new ItemStack(Material.DIAMOND_CHESTPLATE));
        assertThat(player.getInventory().getItemInOffHand()).as("off-hand untouched")
                .isEqualTo(new ItemStack(Material.TORCH, 4));
        assertThat(player.getEnderChest().getItem(0)).as("ender chest untouched")
                .isEqualTo(new ItemStack(Material.APPLE, 5));
        assertThat(player.getLevel()).as("experience untouched").isEqualTo(30);
    }

    private BackupContent backupOfBackedUpState() {
        giveBackedUpState();
        return BackupContent.fromPlayer(player, true, true, true);
    }

    @Test
    @DisplayName("Control: a readable backup restores every part it holds")
    void readableBackupRestores() {
        BackupContent content = backupOfBackedUpState();
        giveCurrentState();

        content.restoreToPlayer(player, true, true, true);

        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
        assertThat(player.getInventory().getItem(5)).isEqualTo(new ItemStack(Material.OAK_LOG, 16));
        assertThat(player.getInventory().getItem(1)).as("the inventory was replaced").isNull();
        assertThat(player.getInventory().getHelmet()).isEqualTo(new ItemStack(Material.IRON_HELMET));
        assertThat(player.getInventory().getItemInOffHand()).isEqualTo(new ItemStack(Material.SHIELD));
        assertThat(player.getEnderChest().getItem(2)).isEqualTo(new ItemStack(Material.EMERALD, 7));
        assertThat(player.getLevel()).isEqualTo(12);
    }

    @Test
    @DisplayName("Unparsable inventory text: nothing is cleared or applied and the restore throws")
    void unparsableInventoryChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setInventoryContents(content.getInventoryContents().replaceFirst("items:", "items: ["));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_INVENTORY);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Inventory text that parses but holds no item: nothing is cleared or applied")
    void inventoryWithoutAnItemChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setInventoryContents("items:\n  '0': not an item\n");
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Inventory text with no items section at all: nothing is cleared or applied")
    void inventoryWithoutItemsSectionChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setInventoryContents("something: else\n");
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Inventory text with an empty items section: nothing is cleared or applied")
    void inventoryWithEmptyItemsSectionChangesNothing() {
        // serializeItems writes an empty part as blank text, never as an empty section.
        BackupContent content = backupOfBackedUpState();
        content.setInventoryContents("items: {}\n");
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Unreadable armor while the inventory reads fine: the inventory is not applied either")
    void unreadableArmorChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setArmorContents(content.getArmorContents().replaceFirst("items:", "items: ["));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_ARMOR);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Unreadable off-hand: nothing is cleared or applied")
    void unreadableOffhandChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setOffhandItem("item: not an item\n");
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_OFFHAND);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Unreadable ender chest: nothing is cleared or applied")
    void unreadableEnderchestChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setEnderchestContents(content.getEnderchestContents().replaceFirst("items:", "items: ["));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_ENDERCHEST);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("A part the restore does not apply is not read, so it cannot refuse the restore")
    void unreadablePartThatIsNotRestoredDoesNotRefuse() {
        BackupContent content = backupOfBackedUpState();
        content.setEnderchestContents("items: [");
        giveCurrentState();

        content.restoreToPlayer(player, true, false, true);

        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
        assertThat(player.getEnderChest().getItem(0))
                .as("the ender chest was not part of this restore")
                .isEqualTo(new ItemStack(Material.APPLE, 5));
    }

    @Test
    @DisplayName("A genuinely empty inventory part still restores, as empty")
    void emptyInventoryPartStillRestores() {
        player.getInventory().clear();
        player.getEnderChest().clear();
        BackupContent content = BackupContent.fromPlayer(player, true, true, true);
        assertThat(content.getInventoryContents()).as("precondition: an empty inventory is stored as blank").isEmpty();
        giveCurrentState();

        content.restoreToPlayer(player, true, true, false);

        assertThat(player.getInventory().getItem(1))
                .as("restoring an empty inventory leaves it empty, as before")
                .isNull();
    }

    @Test
    @DisplayName("An armor part with a slot beyond the four armor slots is refused before anything changes")
    void armorSlotBeyondTheArmorSlotsChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setArmorContents("items:\n  '4':\n" + itemYaml(new ItemStack(Material.IRON_BOOTS)));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_ARMOR);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("An ender chest part with a slot beyond its 27 slots is refused before anything changes")
    void enderchestSlotBeyondItsSizeChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setEnderchestContents("items:\n  '27':\n" + itemYaml(new ItemStack(Material.EMERALD)));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_ENDERCHEST);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("An inventory part with a slot beyond the 36 storage slots is refused before anything changes")
    void inventorySlotBeyondStorageChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setInventoryContents("items:\n  '36':\n" + itemYaml(new ItemStack(Material.DIAMOND)));
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo(BackupContent.PART_INVENTORY);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("The preview reads each part with that part's own slot range")
    void previewGettersUseEachPartsCapacity() {
        BackupContent content = backupOfBackedUpState();
        content.setArmorContents("items:\n  '4':\n" + itemYaml(new ItemStack(Material.IRON_BOOTS)));
        content.setEnderchestContents("items:\n  '30':\n" + itemYaml(new ItemStack(Material.EMERALD)));

        assertThat(content.getArmorItems())
                .as("an armor slot outside the four armor slots is unreadable here too, as findUnreadablePart says")
                .isNull();
        assertThat(content.getEnderchestItems())
                .as("an ender chest slot outside its 27 slots is unreadable here too")
                .isNull();
        assertThat(content.getInventoryItems()).as("control: the inventory part still reads").isNotNull();
    }

    @Test
    @DisplayName("With armor not restored, the worn armor and off-hand are kept (UltiBackup#25)")
    void armorNotRestoredKeepsWornArmorAndOffhand() {
        BackupContent content = backupOfBackedUpState();
        giveCurrentState();

        content.restoreToPlayer(player, false, true, true);

        assertThat(player.getInventory().getChestplate())
                .as("backup_armor: false must not take the armor the player is wearing")
                .isEqualTo(new ItemStack(Material.DIAMOND_CHESTPLATE));
        assertThat(player.getInventory().getItemInOffHand()).as("nor the off-hand item")
                .isEqualTo(new ItemStack(Material.TORCH, 4));
        assertThat(player.getInventory().getItem(0)).as("the storage slots were restored")
                .isEqualTo(new ItemStack(Material.DIAMOND, 3));
        assertThat(player.getInventory().getItem(1)).as("and replaced, as before").isNull();
    }

    @Test
    @DisplayName("Control: with armor restored, the worn armor is replaced by the backup's")
    void armorRestoredReplacesWornArmor() {
        BackupContent content = backupOfBackedUpState();
        giveCurrentState();

        content.restoreToPlayer(player, true, true, true);

        assertThat(player.getInventory().getChestplate()).as("the backup held no chestplate").isNull();
        assertThat(player.getInventory().getHelmet()).isEqualTo(new ItemStack(Material.IRON_HELMET));
        assertThat(player.getInventory().getItemInOffHand()).isEqualTo(new ItemStack(Material.SHIELD));
    }

    @Test
    @DisplayName("Through forceRestore with backup_armor: false, the worn armor is kept (UltiBackup#25)")
    void forceRestoreWithBackupArmorOffKeepsWornArmor() throws Exception {
        File file = tempDir.resolve("no-armor.yml").toFile();
        giveBackedUpState();
        BackupContent.fromPlayer(player, false, true, true).saveToFile(file);
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        BackupConfig config = UltiBackupTestHelper.createDefaultConfig();
        org.mockito.Mockito.when(config.isBackupArmor()).thenReturn(false);
        UltiBackupTestHelper.setField(service, "config", config);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.SUCCESS);
        assertThat(player.getInventory().getChestplate()).isEqualTo(new ItemStack(Material.DIAMOND_CHESTPLATE));
        assertThat(player.getInventory().getItemInOffHand()).isEqualTo(new ItemStack(Material.TORCH, 4));
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
    }

    @Test
    @DisplayName("A backup taken without armor, restored with armor on, keeps the worn armor and off-hand (UltiBackup#25)")
    void backupWithoutArmorRestoredWithArmorOnKeepsWornArmor() throws Exception {
        File file = tempDir.resolve("taken-without-armor.yml").toFile();
        giveBackedUpState();
        BackupContent.fromPlayer(player, false, true, true).saveToFile(file);
        BackupContent loaded = BackupContent.loadFromFile(file);
        giveCurrentState();

        loaded.restoreToPlayer(player, true, true, true);

        assertThat(player.getInventory().getChestplate())
                .as("the backup has no armor to put back, so the worn armor is not taken")
                .isEqualTo(new ItemStack(Material.DIAMOND_CHESTPLATE));
        assertThat(player.getInventory().getItemInOffHand()).isEqualTo(new ItemStack(Material.TORCH, 4));
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
    }

    @Test
    @DisplayName("Control: a backup taken with armor while none was worn, restored with armor on, empties the armor slots")
    void backupWithEmptyArmorRestoredWithArmorOnEmptiesArmor() throws Exception {
        File file = tempDir.resolve("taken-with-no-armor-worn.yml").toFile();
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        BackupContent.fromPlayer(player, true, true, true).saveToFile(file);
        BackupContent loaded = BackupContent.loadFromFile(file);
        giveCurrentState();

        loaded.restoreToPlayer(player, true, true, true);

        assertThat(player.getInventory().getChestplate()).as("the backup's armor, empty, was restored").isNull();
        assertThat(player.getInventory().getItemInOffHand().getType().isAir()).isTrue();
    }

    // ==================== Experience values the server would refuse ====================
    // Player#setLevel refuses a negative level and Player#setExp a progress outside 0-1 (NaN
    // included), and both run after the inventories are replaced, so they are checked before
    // anything is cleared.

    @Test
    @DisplayName("A negative experience level is refused before anything changes")
    void negativeExpLevelChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setExpLevel(-1);
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo("expLevel");
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Experience progress above 1 is refused before anything changes")
    void expProgressAboveOneChangesNothing() {
        BackupContent content = backupOfBackedUpState();
        content.setExpProgress(1.5f);
        giveCurrentState();

        assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                .isInstanceOf(BackupContent.UnreadablePartException.class)
                .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                .isEqualTo("expProgress");
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("Negative or NaN experience progress is refused before anything changes")
    void expProgressNegativeOrNanChangesNothing() {
        for (float progress : new float[] {-0.25f, Float.NaN}) {
            BackupContent content = backupOfBackedUpState();
            content.setExpProgress(progress);
            giveCurrentState();

            assertThatThrownBy(() -> content.restoreToPlayer(player, true, true, true))
                    .as("progress %s", progress)
                    .isInstanceOf(BackupContent.UnreadablePartException.class)
                    .extracting(e -> ((BackupContent.UnreadablePartException) e).getPart())
                    .isEqualTo("expProgress");
            assertCurrentStateUnchanged();
        }
    }

    @Test
    @DisplayName("Control: experience values at the edges of the range restore")
    void expValuesAtTheEdgesRestore() {
        BackupContent content = backupOfBackedUpState();
        content.setExpLevel(0);
        content.setExpProgress(1.0f);
        giveCurrentState();

        content.restoreToPlayer(player, true, true, true);

        assertThat(player.getLevel()).isZero();
        assertThat(player.getExp()).isEqualTo(1.0f);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));

        // A player who has just levelled up holds exactly 0 progress.
        BackupContent justLevelled = backupOfBackedUpState();
        justLevelled.setExpProgress(0.0f);
        giveCurrentState();
        player.setExp(0.5f);

        justLevelled.restoreToPlayer(player, true, true, true);

        assertThat(player.getExp()).isZero();
        assertThat(player.getLevel()).isEqualTo(12);
    }

    @Test
    @DisplayName("With experience not restored, a bad experience value does not refuse the restore")
    void badExpValueNotRestoredDoesNotRefuse() {
        BackupContent content = backupOfBackedUpState();
        content.setExpLevel(-1);
        giveCurrentState();

        content.restoreToPlayer(player, true, true, false);

        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
        assertThat(player.getLevel()).as("experience was not part of this restore").isEqualTo(30);
    }

    @Test
    @DisplayName("Through forceRestore, a negative experience level reports failure and changes nothing")
    void forceRestoreWithNegativeExpLevelFailsAndChangesNothing() throws Exception {
        File file = tempDir.resolve("negative-level.yml").toFile();
        giveBackedUpState();
        BackupContent backup = BackupContent.fromPlayer(player, true, true, true);
        backup.setExpLevel(-3);
        backup.saveToFile(file);
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.RESTORE_FAILED);
        assertCurrentStateUnchanged();
    }

    /** One item as the module's own serializer writes it under an items.N key, indented for that key. */
    private static String itemYaml(ItemStack item) {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("x", item);
        StringBuilder out = new StringBuilder();
        for (String line : yaml.saveToString().split("\n")) {
            if (line.startsWith("x:")) {
                continue;
            }
            out.append("  ").append(line).append('\n');
        }
        return out.toString();
    }

    // ==================== Through BackupService#forceRestore, with a real file ====================

    private BackupService serviceWith(File backupFile, BackupMetadata[] metadataOut) throws Exception {
        BackupService service = new BackupService();
        BackupConfig config = UltiBackupTestHelper.createDefaultConfig();
        UltiBackupTestHelper.setField(service, "plugin", UltiBackupTestHelper.getMockPlugin());
        UltiBackupTestHelper.setField(service, "config", config);
        @SuppressWarnings("unchecked")
        DataOperator<BackupMetadata> operator = mock(DataOperator.class);
        UltiBackupTestHelper.setField(service, "dataOperator", operator);
        UltiBackupTestHelper.setField(service, "backupsDirectory", tempDir.toFile());

        BackupMetadata metadata = spy(BackupMetadata.builder().filePath(backupFile.getName()).build());
        metadata.setId("backup-21");
        doReturn(backupFile).when(metadata).getBackupFile();
        metadataOut[0] = metadata;
        return service;
    }

    /**
     * The edit the checklist row makes to a real backup file: the inventory block's first line
     * {@code items:} becomes {@code items: [}, which no YAML reader can parse.
     */
    private static void breakInventoryBlock(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int inventory = text.indexOf("inventory:");
        int items = text.indexOf("items:", inventory);
        assertThat(inventory).as("precondition: the file has an inventory block").isNotNegative();
        assertThat(items).as("precondition: the inventory block holds items").isGreaterThan(inventory);
        String edited = text.substring(0, items) + "items: [" + text.substring(items + "items:".length());
        Files.write(file.toPath(), edited.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Control: forceRestore of an unedited file restores and answers SUCCESS")
    void forceRestoreOfReadableFileSucceeds() throws Exception {
        File file = tempDir.resolve("readable.yml").toFile();
        backupOfBackedUpState().saveToFile(file);
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.SUCCESS);
        assertThat(player.getInventory().getItem(0)).isEqualTo(new ItemStack(Material.DIAMOND, 3));
    }

    @Test
    @DisplayName("forceRestore of a file whose inventory block is broken answers RESTORE_FAILED and changes nothing")
    void forceRestoreOfBrokenInventoryFails() throws Exception {
        File file = tempDir.resolve("broken.yml").toFile();
        backupOfBackedUpState().saveToFile(file);
        breakInventoryBlock(file);
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.RESTORE_FAILED);
        assertCurrentStateUnchanged();
        verify(UltiBackupTestHelper.getMockLogger())
                .warn(any(BackupContent.UnreadablePartException.class), contains("backup.log.restore_unreadable"));
        verify(UltiBackupTestHelper.getMockLogger(), never()).info(contains("backup.log.restored"));
    }

    @Test
    @DisplayName("forceRestore of a file cut off after its inventory block answers LOAD_FAILED and changes nothing")
    void forceRestoreOfFileTruncatedAfterInventoryFails() throws Exception {
        File file = tempDir.resolve("cut.yml").toFile();
        backupOfBackedUpState().saveToFile(file);
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        int armor = text.indexOf("\narmor:");
        assertThat(armor).as("precondition: the armor section follows the inventory block").isPositive();
        // saveToFile writes the whole file in one pass with no temporary file, so a crash or a full
        // disk leaves a prefix: the inventory block, which is written first, and nothing after it.
        Files.write(file.toPath(), text.substring(0, armor + 1).getBytes(StandardCharsets.UTF_8));
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.LOAD_FAILED);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("forceRestore of an empty file answers LOAD_FAILED and changes nothing")
    void forceRestoreOfEmptyFileFails() throws Exception {
        File file = tempDir.resolve("emptied.yml").toFile();
        backupOfBackedUpState().saveToFile(file);
        Files.write(file.toPath(), new byte[0]);
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.LOAD_FAILED);
        assertCurrentStateUnchanged();
    }

    @Test
    @DisplayName("forceRestore of a file that is not YAML at all answers LOAD_FAILED and changes nothing")
    void forceRestoreOfUnparsableFileFails() throws Exception {
        File file = tempDir.resolve("garbage.yml").toFile();
        Files.write(file.toPath(), "inventory: [\n  : : :\n".getBytes(StandardCharsets.UTF_8));
        BackupMetadata[] metadata = new BackupMetadata[1];
        BackupService service = serviceWith(file, metadata);
        giveCurrentState();

        BackupService.RestoreResult result = service.forceRestore(player, metadata[0]);

        assertThat(result).isEqualTo(BackupService.RestoreResult.LOAD_FAILED);
        assertCurrentStateUnchanged();
        verify(UltiBackupTestHelper.getMockLogger(), never()).info(anyString());
    }
}
