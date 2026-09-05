package com.ultikits.plugins.backup;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reopen-guard sentinel for the module's test-time server bootstrap (Phase 14).
 * <p>
 * Every assertion below depends on a live server having been bootstrapped via
 * {@code MockBukkit.mock()} -- never on a bare registry constant, which
 * {@code mockbukkit-v1.21} resolves via {@link java.util.ServiceLoader} merely from being on the
 * classpath, independent of whether a live server was ever mocked. If this class ever goes green
 * without a live-server bootstrap present, the guard has been silently defeated.
 */
class UltiBackupRegistrySentinelTest {

    @Test
    void liveServerIsBootstrapped() {
        assertNotNull(Bukkit.getServer(), "live server bootstrap must be present");
    }

    @Test
    void unsafeValuesResolves() {
        assertNotNull(Bukkit.getUnsafe(), "UnsafeValues must resolve on a live server");
    }

    @Test
    void createProfileDoesNotSilentlyReturnNull() {
        Object profile = Bukkit.createProfile(UUID.randomUUID(), "SentinelPlayer");
        assertNotNull(profile, "createProfile must not silently return null");
    }

    @Test
    void itemStackConstructionResolvesRegistry() {
        ItemStack stack = new ItemStack(Material.DIAMOND);
        assertNotNull(stack);
        assertEquals(Material.DIAMOND, stack.getType());
    }
}
