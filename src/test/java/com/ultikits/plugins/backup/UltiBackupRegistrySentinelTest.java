package com.ultikits.plugins.backup;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reopen-guard sentinel for the module's test-time server bootstrap (Phase 14).
 * <p>
 * Every assertion below depends on a live server having been bootstrapped -- never on a bare
 * registry constant, which {@code mockbukkit-v1.21} resolves via {@link java.util.ServiceLoader}
 * merely from being on the classpath, independent of whether a live server was ever mocked.
 * <p>
 * {@link #setUp()}/{@link #tearDown()} route through {@link UltiBackupTestHelper#setUp()} /
 * {@link UltiBackupTestHelper#tearDown()} -- the module's single centralized live-server
 * bootstrap, also used by every GUI test class -- rather than calling
 * {@code MockBukkit.mock()}/{@code MockBukkit.unmock()} directly. Calling {@code MockBukkit.mock()}
 * here directly would let this sentinel construct its own, unrelated live server: if the shared
 * bootstrap in {@code UltiBackupTestHelper} were ever removed or broken, this sentinel would stay
 * green while every real GUI test failed, defeating the guard. Routing through the shared entry
 * point means a broken bootstrap here goes red exactly like it would for the tests it guards.
 */
class UltiBackupRegistrySentinelTest {

    @BeforeEach
    void setUp() throws Exception {
        UltiBackupTestHelper.setUp();
    }

    @AfterEach
    void tearDown() throws Exception {
        UltiBackupTestHelper.tearDown();
    }

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
