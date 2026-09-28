package com.ultikits.plugins.backup.entity;

import com.ultikits.plugins.backup.UltiBackupTestHelper;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A backup taken with {@code backup_exp: false} holds no experience, and restoring it never changes
 * the player's experience, whatever {@code backup_exp} says at restore time (UltiKits/UltiBackup#27,
 * maintainer decision 2026-09-25: the file format stays backward compatible).
 * <p>
 * The player is a MockBukkit player with real experience, and each backup file is written by the
 * module's own {@code fromPlayer} + {@code saveToFile}, or, for the format before this change, by hand
 * exactly as that format was written.
 */
@DisplayName("A backup without experience never changes experience on restore (UltiKits/UltiBackup#27)")
class ExperienceNotCapturedTest {

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

    private File savedWithout(String name) throws IOException {
        player.getInventory().clear();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.setLevel(12);
        player.setExp(0.25f);
        File file = tempDir.resolve(name).toFile();
        BackupContent.fromPlayer(player, true, true, false).saveToFile(file);
        return file;
    }

    private File written(String name, String body) throws IOException {
        File file = tempDir.resolve(name).toFile();
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write(body);
        }
        return file;
    }

    @Test
    @DisplayName("taken with backup_exp false, restored with backup_exp true: the level and progress stay")
    void levelStays() throws Exception {
        File file = savedWithout("no-exp.yml");
        player.getInventory().clear();
        player.setLevel(30);
        player.setExp(0.5f);

        BackupContent.loadFromFile(file).restoreToPlayer(player, true, true, true);

        assertThat(player.getLevel()).isEqualTo(30);
        assertThat(player.getExp()).isEqualTo(0.5f);
        assertThat(player.getInventory().getItem(0))
                .as("control: the rest of the backup was restored")
                .isEqualTo(new ItemStack(Material.DIAMOND, 3));
    }

    @Test
    @DisplayName("the file holds no experience keys when experience was not captured")
    void fileHasNoExperience() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(savedWithout("no-exp-keys.yml"));

        assertThat(yaml.contains("expLevel")).isFalse();
        assertThat(yaml.contains("expProgress")).isFalse();
        assertThat(yaml.contains("inventory")).as("control: the file was read").isTrue();
    }

    @Test
    @DisplayName("control: a backup with experience still restores it")
    void capturedExperienceRestores() throws Exception {
        player.setLevel(12);
        player.setExp(0.25f);
        File file = tempDir.resolve("with-exp.yml").toFile();
        BackupContent.fromPlayer(player, true, true, true).saveToFile(file);
        player.setLevel(30);

        BackupContent.loadFromFile(file).restoreToPlayer(player, true, true, true);

        assertThat(player.getLevel()).isEqualTo(12);
        assertThat(player.getExp()).isEqualTo(0.25f);
    }

    @Test
    @DisplayName("a file in the earlier format, which always carries experience, still loads and restores it")
    void earlierFormatStillRestores() throws Exception {
        File file = written("earlier-format.yml",
                "inventory: ''\narmor: ''\noffhand: ''\nenderchest: ''\nexpLevel: 7\nexpProgress: 0.5\n");
        player.setLevel(30);

        BackupContent.loadFromFile(file).restoreToPlayer(player, true, true, true);

        assertThat(player.getLevel()).isEqualTo(7);
    }

    @Test
    @DisplayName("a file cut off before its end is still refused, with or without experience")
    void truncatedFileIsRefused() throws Exception {
        File withoutExp = written("cut-no-exp.yml", "inventory: ''\narmor: ''\noffhand: ''\n");
        File earlierCut = written("cut-earlier.yml", "inventory: ''\narmor: ''\noffhand: ''\nexpLevel: 7\n");

        assertThatThrownBy(() -> BackupContent.loadFromFile(withoutExp)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> BackupContent.loadFromFile(earlierCut)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("experience level without progress, or progress without level, is refused")
    void halfTheExperienceIsRefused() throws Exception {
        File file = savedWithout("half.yml");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        yaml.set("expLevel", 5);
        yaml.save(file);

        assertThatThrownBy(() -> BackupContent.loadFromFile(file)).isInstanceOf(IOException.class);
    }
}
