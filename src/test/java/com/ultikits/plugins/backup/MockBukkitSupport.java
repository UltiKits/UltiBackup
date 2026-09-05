package com.ultikits.plugins.backup;

import java.lang.reflect.Field;

import org.bukkit.Bukkit;

import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * MockBukkit test support utility.
 * <p>
 * Provides robust MockBukkit cleanup to resolve singleton conflicts between tests. Logic copied
 * from the framework's own
 * {@code com.ultikits.ultitools.utils.MockBukkitHelper} (Phase 14's own pattern map), per
 * CONTEXT.md's "no shared artifact" decision -- no dependency is added on the framework's copy.
 */
@SuppressWarnings("PMD.AvoidAccessibilityAlteration") // Test helper requires reflection for singleton cleanup
public final class MockBukkitSupport {

    private MockBukkitSupport() {
    }

    /**
     * Safely clean up MockBukkit's and Bukkit's singleton state. Call at the start of every
     * test's {@code @BeforeEach}.
     */
    public static void ensureCleanState() {
        try {
            if (MockBukkit.isMocked()) {
                MockBukkit.unmock();
            }
        } catch (Exception ignored) {
        }

        try {
            Field mockedField = MockBukkit.class.getDeclaredField("mocked");
            mockedField.setAccessible(true);
            mockedField.setBoolean(null, false);
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
