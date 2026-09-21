package com.ultikits.plugins.backup;

import com.ultikits.ultitools.interfaces.impl.logger.PluginLogger;

import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("UltiBackup Main Class Tests")
class UltiBackupTest {

    @Test
    @DisplayName("registerSelf should return true")
    void registerSelf() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        PluginLogger logger = mock(PluginLogger.class);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.registerSelf()).thenCallRealMethod();

        boolean result = plugin.registerSelf();

        assertThat(result).isTrue();
        verify(logger).info("UltiBackup has been enabled!");
    }

    @Test
    @DisplayName("declares no override of the framework's final unregisterSelf() (UltiKits/UltiBackup#14)")
    void declaresNoUnregisterSelfOverride() {
        assertThat(declaredMethodNames()).doesNotContain("unregisterSelf");
    }

    @Test
    @DisplayName("declares no override of the framework's final reloadSelf() (UltiKits/UltiBackup#14)")
    void declaresNoReloadSelfOverride() {
        assertThat(declaredMethodNames()).doesNotContain("reloadSelf");
    }

    @Test
    @DisplayName("declares no onUnregister()/onReload() hook: the log-only overrides are deleted, not renamed")
    void declaresNoLifecycleHooks() {
        assertThat(declaredMethodNames()).doesNotContain("onUnregister", "onReload");
    }

    @Test
    @DisplayName("supported should return zh and en")
    void supported() throws Exception {
        UltiBackup plugin = mock(UltiBackup.class);
        when(plugin.supported()).thenCallRealMethod();

        List<String> langs = plugin.supported();

        assertThat(langs).containsExactly("zh", "en");
    }

    private static List<String> declaredMethodNames() {
        List<String> names = new ArrayList<>();
        for (Method method : UltiBackup.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        return names;
    }
}
