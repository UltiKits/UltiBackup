package com.ultikits.plugins.backup.entity;

import com.ultikits.plugins.backup.UltiBackupTestHelper;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BackupMetadata Tests")
class BackupMetadataTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

    // ==================== Factory Method ====================

    @Nested
    @DisplayName("fromPlayer Factory")
    class FromPlayerFactory {

        @Test
        @DisplayName("Should capture player UUID")
        void capturesUuid() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiBackupTestHelper.createMockPlayer("TestPlayer", uuid);

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "MANUAL");

            assertThat(metadata.getPlayerUuid()).isEqualTo(uuid.toString());
        }

        @Test
        @DisplayName("Should capture player name")
        void capturesName() {
            Player player = UltiBackupTestHelper.createMockPlayer("Steve", UUID.randomUUID());

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "DEATH");

            assertThat(metadata.getPlayerName()).isEqualTo("Steve");
        }

        @Test
        @DisplayName("Should capture backup reason")
        void capturesReason() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "AUTO");

            assertThat(metadata.getBackupReason()).isEqualTo("AUTO");
        }

        @Test
        @DisplayName("Should capture location from player")
        void capturesLocation() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "MANUAL");

            assertThat(metadata.getWorldName()).isEqualTo("world");
            assertThat(metadata.getLocationX()).isEqualTo(100.5);
            assertThat(metadata.getLocationY()).isEqualTo(64.0);
            assertThat(metadata.getLocationZ()).isEqualTo(-200.5);
        }

        @Test
        @DisplayName("Should capture experience level")
        void capturesExpLevel() {
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "MANUAL");

            assertThat(metadata.getExpLevel()).isEqualTo(30);
        }

        @Test
        @DisplayName("Should set timestamp close to current time")
        void setsTimestamp() {
            long before = System.currentTimeMillis();
            Player player = UltiBackupTestHelper.createMockPlayer("P", UUID.randomUUID());
            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "MANUAL");
            long after = System.currentTimeMillis();

            assertThat(metadata.getBackupTime()).isBetween(before, after);
        }

        @Test
        @DisplayName("Should auto-generate file path")
        void generatesFilePath() {
            UUID uuid = UUID.randomUUID();
            Player player = UltiBackupTestHelper.createMockPlayer("P", uuid);

            BackupMetadata metadata = BackupMetadata.fromPlayer(player, "MANUAL");

            assertThat(metadata.getFilePath())
                    .startsWith("backups/" + uuid.toString() + "_")
                    .endsWith(".yml");
        }
    }

    // ==================== File Path Generation ====================

    @Nested
    @DisplayName("File Path Generation")
    class FilePathGeneration {

        @Test
        @DisplayName("Should follow format backups/{uuid}_{timestamp}.yml")
        void correctFormat() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .playerUuid("abc-123")
                    .backupTime(1700000000000L)
                    .build();

            assertThat(metadata.generateFilePath())
                    .isEqualTo("backups/abc-123_1700000000000.yml");
        }
    }

    // ==================== Formatted Time ====================

    @Nested
    @DisplayName("Formatted Time")
    class FormattedTime {

        @Test
        @DisplayName("Should format time as yyyy-MM-dd HH:mm:ss")
        void correctFormat() {
            long timestamp = 1700000000000L;
            BackupMetadata metadata = BackupMetadata.builder()
                    .backupTime(timestamp)
                    .build();

            String expected = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(timestamp));

            assertThat(metadata.getFormattedTime()).isEqualTo(expected);
        }
    }

    // ==================== Reason Display ====================

    @Nested
    @DisplayName("Reason Display (i18n)")
    class ReasonDisplay {

        @ParameterizedTest
        @CsvSource({
                "DEATH,  DEATH",
                "QUIT,   QUIT",
                "AUTO,   AUTO",
                "MANUAL, MANUAL",
                "ADMIN,  ADMIN"
        })
        @DisplayName("Should return raw reason string")
        void knownReasons(String reason, String expectedDisplay) {
            BackupMetadata metadata = BackupMetadata.builder()
                    .backupReason(reason)
                    .build();

            assertThat(metadata.getReasonDisplay()).isEqualTo(expectedDisplay);
        }

        @Test
        @DisplayName("Should return raw reason for unrecognized reason")
        void unknownReason() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .backupReason("SOMETHING_ELSE")
                    .build();

            assertThat(metadata.getReasonDisplay()).isEqualTo("SOMETHING_ELSE");
        }
    }

    // ==================== Backup File Resolution ====================
    // Note: getBackupFile() calls UltiTools.getInstance().getDataFolder() which cannot
    // be mocked (UltiTools is a final class). We test the null/empty path guard logic only.

    @Nested
    @DisplayName("Backup File Resolution")
    class BackupFileResolution {

        @Test
        @DisplayName("Should return null when file path is null")
        void nullFilePath() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            assertThat(metadata.getBackupFile()).isNull();
        }

        @Test
        @DisplayName("Should return null when file path is empty")
        void emptyFilePath() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .filePath("")
                    .build();
            assertThat(metadata.getBackupFile()).isNull();
        }
    }

    // ==================== onDelete Lifecycle Hook ====================
    // Note: onDelete() calls getBackupFile() which calls UltiTools.getInstance().
    // We test the null-path guard (no UltiTools call) and use a spy for file deletion.

    @Nested
    @DisplayName("onDelete Lifecycle Hook")
    class OnDeleteHook {

        @Test
        @DisplayName("Should handle gracefully when file path is null")
        void nullFilePathOnDelete() {
            BackupMetadata metadata = BackupMetadata.builder().build();
            assertThatCode(() -> metadata.onDelete()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should handle gracefully when file path is empty")
        void emptyFilePathOnDelete() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .filePath("")
                    .build();
            assertThatCode(() -> metadata.onDelete()).doesNotThrowAnyException();
        }
    }

    // ==================== Setters (@Data) ====================

    @Nested
    @DisplayName("Setters")
    class SetterTests {

        @Test
        @DisplayName("Should set and get all fields via setters")
        void settersWork() {
            BackupMetadata metadata = new BackupMetadata();
            metadata.setPlayerUuid("uuid-set");
            metadata.setPlayerName("SetName");
            metadata.setBackupTime(99999L);
            metadata.setBackupReason("AUTO");
            metadata.setFilePath("backups/set.yml");
            metadata.setChecksum("check-set");
            metadata.setWorldName("nether");
            metadata.setLocationX(10.0);
            metadata.setLocationY(20.0);
            metadata.setLocationZ(30.0);
            metadata.setExpLevel(100);

            assertThat(metadata.getPlayerUuid()).isEqualTo("uuid-set");
            assertThat(metadata.getPlayerName()).isEqualTo("SetName");
            assertThat(metadata.getBackupTime()).isEqualTo(99999L);
            assertThat(metadata.getBackupReason()).isEqualTo("AUTO");
            assertThat(metadata.getFilePath()).isEqualTo("backups/set.yml");
            assertThat(metadata.getChecksum()).isEqualTo("check-set");
            assertThat(metadata.getWorldName()).isEqualTo("nether");
            assertThat(metadata.getLocationX()).isEqualTo(10.0);
            assertThat(metadata.getLocationY()).isEqualTo(20.0);
            assertThat(metadata.getLocationZ()).isEqualTo(30.0);
            assertThat(metadata.getExpLevel()).isEqualTo(100);
        }
    }

    // ==================== No-arg Constructor ====================

    @Nested
    @DisplayName("No-arg Constructor")
    class NoArgConstructor {

        @Test
        @DisplayName("Should create with default values")
        void defaults() {
            BackupMetadata metadata = new BackupMetadata();
            assertThat(metadata.getPlayerUuid()).isNull();
            assertThat(metadata.getPlayerName()).isNull();
            assertThat(metadata.getBackupTime()).isZero();
            assertThat(metadata.getExpLevel()).isZero();
        }
    }

    // ==================== All-args Constructor ====================

    @Nested
    @DisplayName("All-args Constructor")
    class AllArgsConstructor {

        @Test
        @DisplayName("Should create with all fields")
        void allFields() {
            BackupMetadata metadata = new BackupMetadata(
                    "uuid-all", "AllName", 55555L, "DEATH",
                    "backups/all.yml", "check-all", "the_end",
                    5.0, 10.0, 15.0, 25);

            assertThat(metadata.getPlayerUuid()).isEqualTo("uuid-all");
            assertThat(metadata.getPlayerName()).isEqualTo("AllName");
            assertThat(metadata.getBackupTime()).isEqualTo(55555L);
            assertThat(metadata.getBackupReason()).isEqualTo("DEATH");
            assertThat(metadata.getFilePath()).isEqualTo("backups/all.yml");
            assertThat(metadata.getChecksum()).isEqualTo("check-all");
            assertThat(metadata.getWorldName()).isEqualTo("the_end");
            assertThat(metadata.getLocationX()).isEqualTo(5.0);
            assertThat(metadata.getLocationY()).isEqualTo(10.0);
            assertThat(metadata.getLocationZ()).isEqualTo(15.0);
            assertThat(metadata.getExpLevel()).isEqualTo(25);
        }
    }

    // ==================== Builder & Equals ====================

    @Nested
    @DisplayName("Builder and Equality")
    class BuilderEquality {

        @Test
        @DisplayName("Should build with all fields")
        void buildAllFields() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .playerName("Steve")
                    .backupTime(12345L)
                    .backupReason("MANUAL")
                    .filePath("backups/file.yml")
                    .checksum("abc123")
                    .worldName("world")
                    .locationX(1.0)
                    .locationY(2.0)
                    .locationZ(3.0)
                    .expLevel(50)
                    .build();

            assertThat(metadata.getPlayerUuid()).isEqualTo("uuid-1");
            assertThat(metadata.getPlayerName()).isEqualTo("Steve");
            assertThat(metadata.getBackupTime()).isEqualTo(12345L);
            assertThat(metadata.getBackupReason()).isEqualTo("MANUAL");
            assertThat(metadata.getFilePath()).isEqualTo("backups/file.yml");
            assertThat(metadata.getChecksum()).isEqualTo("abc123");
            assertThat(metadata.getWorldName()).isEqualTo("world");
            assertThat(metadata.getLocationX()).isEqualTo(1.0);
            assertThat(metadata.getLocationY()).isEqualTo(2.0);
            assertThat(metadata.getLocationZ()).isEqualTo(3.0);
            assertThat(metadata.getExpLevel()).isEqualTo(50);
        }

        @Test
        @DisplayName("Should implement toString")
        void toStringTest() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .playerName("Steve")
                    .build();

            String str = metadata.toString();
            assertThat(str).contains("uuid-1");
            assertThat(str).contains("Steve");
        }

        @Test
        @DisplayName("Should implement equals and hashCode")
        void equalsAndHashCode() {
            BackupMetadata a = BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .backupTime(100L)
                    .build();
            BackupMetadata b = BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .backupTime(100L)
                    .build();

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Should not be equal when different")
        void notEqual() {
            BackupMetadata a = BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .build();
            BackupMetadata b = BackupMetadata.builder()
                    .playerUuid("uuid-2")
                    .build();

            assertThat(a).isNotEqualTo(b);
        }
    }

    // ==================== Lombok-generated equals()/hashCode() branch coverage ====================
    //
    // BackupMetadata carries @EqualsAndHashCode(callSuper = true) over eleven fields (six String,
    // three double, one long, one int) plus BaseDataEntity's own `id` field via callSuper. Each
    // reference-typed field's generated equals() branch is a null-safe comparison
    // (`this$x == null ? other$x != null : !this$x.equals(other$x)`) that JaCoCo counts as two
    // branches; without pinning both the null and non-null-but-different directions for every
    // field, most of that generated code is never exercised by any test. These tests exist to pin
    // the equals()/hashCode() *contract* (reflexivity, type-safety, per-field discrimination, and
    // hashCode consistency with equals) field by field -- not to move a coverage counter.
    @Nested
    @DisplayName("equals()/hashCode() contract, field by field")
    class EqualsHashCodeContract {

        private BackupMetadata full() {
            return BackupMetadata.builder()
                    .playerUuid("uuid-1")
                    .playerName("Steve")
                    .backupTime(12345L)
                    .backupReason("MANUAL")
                    .filePath("backups/file.yml")
                    .checksum("abc123")
                    .worldName("world")
                    .locationX(1.0)
                    .locationY(2.0)
                    .locationZ(3.0)
                    .expLevel(50)
                    .build();
        }

        @Test
        @DisplayName("Reflexive: an instance equals itself")
        void reflexive() {
            BackupMetadata a = full();
            assertThat(a).isEqualTo(a);
            assertThat(a.equals(a)).isTrue();
        }

        @Test
        @DisplayName("Not equal to null")
        void notEqualToNull() {
            assertThat(full()).isNotEqualTo(null);
        }

        @Test
        @DisplayName("Not equal to an unrelated type")
        void notEqualToUnrelatedType() {
            assertThat(full()).isNotEqualTo("not a BackupMetadata");
            assertThat(full().equals(Integer.valueOf(1))).isFalse();
        }

        @Test
        @DisplayName("Two default (all-null/zero) instances are equal")
        void bothDefaultInstancesEqual() {
            BackupMetadata a = BackupMetadata.builder().build();
            BackupMetadata b = BackupMetadata.builder().build();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Two fully-populated, identical instances are equal with matching hashCode")
        void fullyPopulatedInstancesEqual() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Differs by id (super field) only")
        void differsById() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            a.setId("id-a");
            b.setId("id-b");
            assertThat(a).isNotEqualTo(b);

            BackupMetadata c = full();
            BackupMetadata d = full();
            c.setId("id-shared");
            d.setId(null);
            assertThat(c).isNotEqualTo(d);
        }

        @Test
        @DisplayName("Differs by playerUuid: null vs value, and value vs different value")
        void differsByPlayerUuid() {
            BackupMetadata withUuid = full();
            BackupMetadata withoutUuid = full();
            withoutUuid.setPlayerUuid(null);
            assertThat(withUuid).isNotEqualTo(withoutUuid);
            assertThat(withoutUuid).isNotEqualTo(withUuid);

            BackupMetadata other = full();
            other.setPlayerUuid("uuid-2");
            assertThat(withUuid).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by playerName: null vs value, and value vs different value")
        void differsByPlayerName() {
            BackupMetadata withName = full();
            BackupMetadata withoutName = full();
            withoutName.setPlayerName(null);
            assertThat(withName).isNotEqualTo(withoutName);
            assertThat(withoutName).isNotEqualTo(withName);

            BackupMetadata other = full();
            other.setPlayerName("Alex");
            assertThat(withName).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by backupTime")
        void differsByBackupTime() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            b.setBackupTime(99999L);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Differs by backupReason: null vs value, and value vs different value")
        void differsByBackupReason() {
            BackupMetadata withReason = full();
            BackupMetadata withoutReason = full();
            withoutReason.setBackupReason(null);
            assertThat(withReason).isNotEqualTo(withoutReason);
            assertThat(withoutReason).isNotEqualTo(withReason);

            BackupMetadata other = full();
            other.setBackupReason("AUTO");
            assertThat(withReason).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by filePath: null vs value, and value vs different value")
        void differsByFilePath() {
            BackupMetadata withPath = full();
            BackupMetadata withoutPath = full();
            withoutPath.setFilePath(null);
            assertThat(withPath).isNotEqualTo(withoutPath);
            assertThat(withoutPath).isNotEqualTo(withPath);

            BackupMetadata other = full();
            other.setFilePath("backups/other.yml");
            assertThat(withPath).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by checksum: null vs value, and value vs different value")
        void differsByChecksum() {
            BackupMetadata withChecksum = full();
            BackupMetadata withoutChecksum = full();
            withoutChecksum.setChecksum(null);
            assertThat(withChecksum).isNotEqualTo(withoutChecksum);
            assertThat(withoutChecksum).isNotEqualTo(withChecksum);

            BackupMetadata other = full();
            other.setChecksum("def456");
            assertThat(withChecksum).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by worldName: null vs value, and value vs different value")
        void differsByWorldName() {
            BackupMetadata withWorld = full();
            BackupMetadata withoutWorld = full();
            withoutWorld.setWorldName(null);
            assertThat(withWorld).isNotEqualTo(withoutWorld);
            assertThat(withoutWorld).isNotEqualTo(withWorld);

            BackupMetadata other = full();
            other.setWorldName("nether");
            assertThat(withWorld).isNotEqualTo(other);
        }

        @Test
        @DisplayName("Differs by locationX")
        void differsByLocationX() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            b.setLocationX(999.0);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Differs by locationY")
        void differsByLocationY() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            b.setLocationY(999.0);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Differs by locationZ")
        void differsByLocationZ() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            b.setLocationZ(999.0);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("Differs by expLevel")
        void differsByExpLevel() {
            BackupMetadata a = full();
            BackupMetadata b = full();
            b.setExpLevel(1);
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("hashCode is consistent across repeated calls and changes when a field changes")
        void hashCodeConsistencyAndSensitivity() {
            BackupMetadata a = full();
            int h1 = a.hashCode();
            int h2 = a.hashCode();
            assertThat(h1).isEqualTo(h2);

            BackupMetadata b = full();
            b.setPlayerUuid("different-uuid");
            assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("hashCode of two default instances matches")
        void hashCodeOfDefaultsMatches() {
            BackupMetadata a = BackupMetadata.builder().build();
            BackupMetadata b = BackupMetadata.builder().build();
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("canEqual rejects an instance of an unrelated subclass")
        void canEqualRejectsUnrelatedType() {
            // A minimal subclass whose canEqual() always returns false exercises the
            // `!other.canEqual(this)` branch Lombok generates ahead of the field comparisons --
            // the one branch a same-type field-differs test can never reach, since two
            // BackupMetadata instances always mutually canEqual() each other.
            class NeverEqualBackupMetadata extends BackupMetadata {
                @Override
                protected boolean canEqual(Object other) {
                    return false;
                }
            }
            BackupMetadata a = full();
            NeverEqualBackupMetadata b = new NeverEqualBackupMetadata();
            assertThat(a).isNotEqualTo(b);
        }
    }

    // ==================== Builder Coverage ====================

    @Nested
    @DisplayName("Builder method coverage")
    class BuilderMethodCoverage {

        @Test
        @DisplayName("Should set filePath via builder")
        void builderFilePath() {
            BackupMetadata m = BackupMetadata.builder()
                    .filePath("backups/test.yml")
                    .build();
            assertThat(m.getFilePath()).isEqualTo("backups/test.yml");
        }

        @Test
        @DisplayName("Should set checksum via builder")
        void builderChecksum() {
            BackupMetadata m = BackupMetadata.builder()
                    .checksum("sha256hash")
                    .build();
            assertThat(m.getChecksum()).isEqualTo("sha256hash");
        }

        @Test
        @DisplayName("Should set locationX via builder")
        void builderLocationX() {
            BackupMetadata m = BackupMetadata.builder()
                    .locationX(99.9)
                    .build();
            assertThat(m.getLocationX()).isEqualTo(99.9);
        }

        @Test
        @DisplayName("Should set locationY via builder")
        void builderLocationY() {
            BackupMetadata m = BackupMetadata.builder()
                    .locationY(128.5)
                    .build();
            assertThat(m.getLocationY()).isEqualTo(128.5);
        }

        @Test
        @DisplayName("Should set locationZ via builder")
        void builderLocationZ() {
            BackupMetadata m = BackupMetadata.builder()
                    .locationZ(-500.3)
                    .build();
            assertThat(m.getLocationZ()).isEqualTo(-500.3);
        }

        @Test
        @DisplayName("Should set worldName via builder")
        void builderWorldName() {
            BackupMetadata m = BackupMetadata.builder()
                    .worldName("the_end")
                    .build();
            assertThat(m.getWorldName()).isEqualTo("the_end");
        }

        @Test
        @DisplayName("Should set expLevel via builder")
        void builderExpLevel() {
            BackupMetadata m = BackupMetadata.builder()
                    .expLevel(100)
                    .build();
            assertThat(m.getExpLevel()).isEqualTo(100);
        }

        @Test
        @DisplayName("Builder toString should contain class name")
        void builderToString() {
            String str = BackupMetadata.builder().toString();
            assertThat(str).contains("BackupMetadata");
        }
    }

    // ==================== Reason Display null case ====================

    @Nested
    @DisplayName("Reason Display null")
    class ReasonDisplayNull {

        @Test
        @DisplayName("Should return UNKNOWN when reason is null")
        void nullReason() {
            BackupMetadata metadata = BackupMetadata.builder()
                    .backupReason(null)
                    .build();

            assertThat(metadata.getReasonDisplay()).isEqualTo("UNKNOWN");
        }
    }

    // ==================== getBackupFile with valid path ====================

    @Nested
    @DisplayName("getBackupFile with Bukkit mock")
    class GetBackupFileWithMock {

        @Test
        @DisplayName("Should return File object when path is set and Bukkit available")
        void returnsFileWithPath() {
            try (MockedStatic<org.bukkit.Bukkit> bukkitMock =
                         org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                File dataFolder = tempDir.toFile();
                org.bukkit.plugin.Plugin ultiToolsPlugin = mock(org.bukkit.plugin.Plugin.class);
                when(ultiToolsPlugin.getDataFolder()).thenReturn(dataFolder);

                org.bukkit.plugin.PluginManager pm = mock(org.bukkit.plugin.PluginManager.class);
                when(pm.getPlugin("UltiTools")).thenReturn(ultiToolsPlugin);
                bukkitMock.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pm);

                BackupMetadata metadata = BackupMetadata.builder()
                        .filePath("backups/test_123.yml")
                        .build();

                File result = metadata.getBackupFile();
                assertThat(result).isNotNull();
                assertThat(result.getPath()).contains("backups");
            }
        }
    }

    // ==================== onDelete with Bukkit mock ====================

    @Nested
    @DisplayName("onDelete with real file")
    class OnDeleteWithFile {

        @Test
        @DisplayName("Should delete the backup file when it exists")
        void deletesFile() throws Exception {
            // Create a real file
            File backupsDir = tempDir.resolve("backups").toFile();
            backupsDir.mkdirs();
            File backupFile = new File(backupsDir, "to_delete.yml");
            backupFile.createNewFile();
            assertThat(backupFile).exists();

            try (MockedStatic<org.bukkit.Bukkit> bukkitMock =
                         org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                org.bukkit.plugin.Plugin ultiToolsPlugin = mock(org.bukkit.plugin.Plugin.class);
                when(ultiToolsPlugin.getDataFolder()).thenReturn(tempDir.toFile());

                org.bukkit.plugin.PluginManager pm = mock(org.bukkit.plugin.PluginManager.class);
                when(pm.getPlugin("UltiTools")).thenReturn(ultiToolsPlugin);
                bukkitMock.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pm);

                BackupMetadata metadata = BackupMetadata.builder()
                        .filePath("backups/to_delete.yml")
                        .build();

                metadata.onDelete();
            }

            assertThat(backupFile).doesNotExist();
        }

        @Test
        @DisplayName("Should handle gracefully when file does not exist")
        void fileNotExist() {
            try (MockedStatic<org.bukkit.Bukkit> bukkitMock =
                         org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
                org.bukkit.plugin.Plugin ultiToolsPlugin = mock(org.bukkit.plugin.Plugin.class);
                when(ultiToolsPlugin.getDataFolder()).thenReturn(tempDir.toFile());

                org.bukkit.plugin.PluginManager pm = mock(org.bukkit.plugin.PluginManager.class);
                when(pm.getPlugin("UltiTools")).thenReturn(ultiToolsPlugin);
                bukkitMock.when(org.bukkit.Bukkit::getPluginManager).thenReturn(pm);

                BackupMetadata metadata = BackupMetadata.builder()
                        .filePath("backups/nonexistent_file.yml")
                        .build();

                assertThatCode(() -> metadata.onDelete()).doesNotThrowAnyException();
            }
        }
    }
}
