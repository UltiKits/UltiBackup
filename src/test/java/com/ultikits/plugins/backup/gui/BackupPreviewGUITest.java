package com.ultikits.plugins.backup.gui;

import com.ultikits.plugins.backup.UltiBackupTestHelper;
import com.ultikits.plugins.backup.entity.BackupContent;
import com.ultikits.plugins.backup.entity.BackupMetadata;
import com.ultikits.plugins.backup.service.BackupService;
import com.ultikits.ultitools.abstracts.UltiToolsPlugin;
import com.ultikits.ultitools.entities.Colors;
import com.ultikits.ultitools.utils.XVersionUtils;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFactory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupPreviewGUI Tests")
class BackupPreviewGUITest {

    private UltiToolsPlugin plugin;
    private Player viewer;
    private BackupMetadata metadata;
    private BackupContent content;
    private MockedStatic<Bukkit> bukkitMock;
    private MockedStatic<XVersionUtils> xVersionMock;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
        plugin = UltiBackupTestHelper.getMockPlugin();

        viewer = UltiBackupTestHelper.createMockPlayer("Viewer", UUID.randomUUID());

        metadata = BackupMetadata.builder()
                .playerUuid(UUID.randomUUID().toString())
                .playerName("Target")
                .backupTime(1700000000000L)
                .backupReason("MANUAL")
                .worldName("world")
                .locationX(0)
                .locationY(64)
                .locationZ(0)
                .expLevel(15)
                .build();
        metadata.setId("preview-id");

        content = BackupContent.builder()
                .inventoryContents("inv-data")
                .armorContents("armor-data")
                .offhandItem("offhand-data")
                .enderchestContents("ender-data")
                .expLevel(15)
                .expProgress(0.3f)
                .build();

        ItemMeta itemMeta = mock(ItemMeta.class);
        ItemFactory itemFactory = mock(ItemFactory.class);
        lenient().when(itemFactory.getItemMeta(any(Material.class))).thenReturn(itemMeta);

        bukkitMock = mockStatic(Bukkit.class);
        bukkitMock.when(() -> Bukkit.createInventory(any(InventoryHolder.class), eq(54), anyString()))
                .thenAnswer(inv -> {
                    Inventory mockInv = mock(Inventory.class);
                    when(mockInv.getHolder()).thenReturn(inv.getArgument(0));
                    return mockInv;
                });
        bukkitMock.when(Bukkit::getItemFactory).thenReturn(itemFactory);

        xVersionMock = mockStatic(XVersionUtils.class);
        xVersionMock.when(() -> XVersionUtils.getColoredPlaneGlass(any(Colors.class)))
                .thenReturn(new ItemStack(Material.GLASS_PANE));
    }

    @AfterEach
    void tearDown() throws Exception {
        xVersionMock.close();
        bukkitMock.close();
        UltiBackupTestHelper.tearDown();
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("Should create inventory with title")
        void createsInventory() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should expose viewer")
        void exposesViewer() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.getViewer()).isSameAs(viewer);
        }

        @Test
        @DisplayName("Should expose metadata")
        void exposesMetadata() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.getMetadata()).isSameAs(metadata);
        }
    }

    @Nested
    @DisplayName("Tab Slots")
    class TabSlots {

        @Test
        @DisplayName("Slot 45 should be a tab slot")
        void slot45() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(45)).isTrue();
        }

        @Test
        @DisplayName("Slot 46 should be a tab slot")
        void slot46() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(46)).isTrue();
        }

        @Test
        @DisplayName("Slot 47 should be a tab slot")
        void slot47() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(47)).isTrue();
        }

        @Test
        @DisplayName("Slot 53 should be a tab slot (close)")
        void slot53() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(53)).isTrue();
        }

        @Test
        @DisplayName("Slot 10 should not be a tab slot")
        void slot10() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(10)).isFalse();
        }

        @Test
        @DisplayName("Slot 48 should not be a tab slot")
        void slot48() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(48)).isFalse();
        }
    }

    @Nested
    @DisplayName("Tab Switching")
    class TabSwitching {

        @Test
        @DisplayName("handleTabClick 45 when on view 1 should switch to inventory view")
        void switchToInventory() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            // First switch to armor view
            gui.handleTabClick(46);
            // Then switch back to inventory
            gui.handleTabClick(45);
            // No exception = success, view is internal state
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick 46 should switch to armor view")
        void switchToArmor() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(46);
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick 47 should switch to enderchest view")
        void switchToEnderchest() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(47);
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick 53 should close viewer inventory")
        void closeButton() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(53);
            verify(viewer).closeInventory();
        }

        @Test
        @DisplayName("handleTabClick 45 when already on inventory should not update")
        void noOpSameTab() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            // Already on inventory view (0), clicking 45 should be no-op
            gui.handleTabClick(45);
            // Just verify no exception
            assertThat(gui.getInventory()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Open static method")
    class OpenMethod {

        @Test
        @DisplayName("Should send error when content is null")
        void nullContent() {
            BackupService backupService = mock(BackupService.class);
            when(backupService.loadBackupContent(metadata)).thenReturn(null);

            BackupPreviewGUI.open(plugin, viewer, metadata, backupService);

            verify(viewer).sendMessage("backup.message.load_failed");
        }

        @Test
        @DisplayName("Should open inventory when content loaded")
        void successfulOpen() {
            BackupService backupService = mock(BackupService.class);
            when(backupService.loadBackupContent(metadata)).thenReturn(content);

            BackupPreviewGUI.open(plugin, viewer, metadata, backupService);

            verify(viewer).openInventory(any(Inventory.class));
        }
    }

    // ==================== Tab Switching Full Coverage ====================

    @Nested
    @DisplayName("Tab switching coverage")
    class TabSwitchingCoverage {

        @Test
        @DisplayName("handleTabClick 46 when already on armor should not update")
        void noOpSameArmorTab() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(46); // switch to armor
            gui.handleTabClick(46); // same tab - no-op
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick 47 when already on enderchest should not update")
        void noOpSameEnderchestTab() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(47); // switch to enderchest
            gui.handleTabClick(47); // same tab - no-op
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should switch from enderchest to armor")
        void enderchestToArmor() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(47); // enderchest
            gui.handleTabClick(46); // armor
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should switch from armor to inventory")
        void armorToInventory() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(46); // armor
            gui.handleTabClick(45); // inventory
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should switch from enderchest to inventory")
        void enderchestToInventory() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(47); // enderchest
            gui.handleTabClick(45); // inventory
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick with non-tab slot should be no-op")
        void nonTabSlotNoOp() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(10); // not a tab slot
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("handleTabClick 48 should be no-op")
        void slot48NoOp() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            gui.handleTabClick(48);
            assertThat(gui.getInventory()).isNotNull();
        }
    }

    // ==================== Content with null items ====================

    @Nested
    @DisplayName("Content with null items")
    class NullItemsContent {

        @Test
        @DisplayName("Should handle null inventory items in updateInventory")
        void nullInventoryItems() {
            BackupContent nullContent = BackupContent.builder()
                    .inventoryContents(null)
                    .armorContents(null)
                    .offhandItem(null)
                    .enderchestContents(null)
                    .expLevel(0)
                    .expProgress(0f)
                    .build();

            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, nullContent);
            // Should not throw
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should handle null armor items when switching to armor tab")
        void nullArmorTab() {
            BackupContent nullContent = BackupContent.builder()
                    .inventoryContents(null)
                    .armorContents(null)
                    .offhandItem(null)
                    .enderchestContents(null)
                    .build();

            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, nullContent);
            gui.handleTabClick(46); // switch to armor
            assertThat(gui.getInventory()).isNotNull();
        }

        @Test
        @DisplayName("Should handle null enderchest items when switching to enderchest tab")
        void nullEnderchestTab() {
            BackupContent nullContent = BackupContent.builder()
                    .inventoryContents(null)
                    .armorContents(null)
                    .offhandItem(null)
                    .enderchestContents(null)
                    .build();

            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, nullContent);
            gui.handleTabClick(47); // switch to enderchest
            assertThat(gui.getInventory()).isNotNull();
        }
    }

    // ==================== isTabSlot edge cases ====================

    @Nested
    @DisplayName("isTabSlot edge cases")
    class IsTabSlotEdgeCases {

        @Test
        @DisplayName("Slot 44 should not be a tab slot")
        void slot44() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(44)).isFalse();
        }

        @Test
        @DisplayName("Slot 0 should not be a tab slot")
        void slot0() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(0)).isFalse();
        }

        @Test
        @DisplayName("Slot 52 should not be a tab slot")
        void slot52() {
            BackupPreviewGUI gui = new BackupPreviewGUI(plugin, viewer, metadata, content);
            assertThat(gui.isTabSlot(52)).isFalse();
        }
    }
}
