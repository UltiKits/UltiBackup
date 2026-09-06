package com.ultikits.plugins.backup;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * MockBukkit test support utility.
 * <p>
 * Provides MockBukkit cleanup that survives a partially-failed teardown, so one test class
 * cannot leave singleton state behind that makes the next class fail when it tries to install
 * its own server.
 * <p>
 * This module carries its own copy of the logic rather than depending on the framework's
 * equivalent helper. The framework's copy lives in its test sources, which are not part of the
 * published {@code com.ultikits:UltiTools-API} artifact, so a module build has nothing to
 * depend on.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for singleton cleanup
public final class MockBukkitSupport {

    private MockBukkitSupport() {
    }

    /**
     * Safely clean up MockBukkit's and Bukkit's singleton state. Call at the start of every
     * test's {@code @BeforeEach}.
     * <p>
     * On the happy path {@code MockBukkit.unmock()} already clears both singletons itself, so
     * the reflective steps below change nothing. They are a guard for the failure path: in
     * MockBukkit 4.101.0 the {@code try}/{@code finally} inside {@code unmock()} does not cover
     * its {@code disablePlugins()} call, so an exception thrown from a plugin's disable logic
     * escapes before {@code setServerInstanceToNull()} runs and leaves MockBukkit's server field
     * populated. The next attempt to install a server would then fail with
     * {@code IllegalStateException: "Already mocking"}. Clearing the field here makes that
     * recoverable.
     */
    public static void ensureCleanState() {
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Exception ignored) {
        }

        // MockBukkit 4.101.0 declares exactly one field, "private static ServerMock mock", and
        // both isMocked() and the "Already mocking" guard read it. Clearing it is what makes a
        // failed unmock() recoverable.
        try {
            Field mockField = MockBukkit.class.getDeclaredField("mock");
            mockField.setAccessible(true);
            mockField.set(null, null);
        } catch (Exception ignored) {
        }

        if (Bukkit.getServer() != null) {
            try {
                Field serverField = Bukkit.class.getDeclaredField("server");
                serverField.setAccessible(true);
                serverField.set(null, null);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Safely unmock MockBukkit. Call at the end of every test's {@code @AfterEach}.
     */
    public static void safeUnmock() {
        try {
            MockBukkit.unmock();
        } catch (Exception ignored) {
        }
        ensureCleanState();
    }
}
