/**
 * MCP Server for EDT
 * Copyright (C) 2025 DitriX (https://github.com/DitriXNew)
 * Licensed under AGPL-3.0-or-later
 */

package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ConsentSettingsService.Level;

/**
 * Tests for {@link ConsentSettingsService.Level} pure preference-token mapping.
 * Exercises the enum round-trip without requiring the Eclipse runtime or SWT.
 * The full env&gt;headless&gt;session&gt;level&gt;per-tool decision table is asserted by
 * {@code DestructiveConsentGateTest}.
 */
public class ConsentSettingsServiceTest
{
    @Test
    public void testDefaultLevelIsAskAlways()
    {
        assertEquals(Level.ASK_ALWAYS,
            Level.fromPreferenceValue(PreferenceConstants.DEFAULT_DESTRUCTIVE_CONSENT_LEVEL));
    }

    @Test
    public void testFromPreferenceValueAskAlways()
    {
        assertEquals(Level.ASK_ALWAYS,
            Level.fromPreferenceValue(PreferenceConstants.CONSENT_LEVEL_ASK_ALWAYS));
    }

    @Test
    public void testFromPreferenceValueAllowAll()
    {
        assertEquals(Level.ALLOW_ALL,
            Level.fromPreferenceValue(PreferenceConstants.CONSENT_LEVEL_ALLOW_ALL));
    }

    @Test
    public void testFromPreferenceValuePerTool()
    {
        assertEquals(Level.PER_TOOL,
            Level.fromPreferenceValue(PreferenceConstants.CONSENT_LEVEL_PER_TOOL));
    }

    @Test
    public void testFromPreferenceValueNullFallsBackToAskAlways()
    {
        assertEquals(Level.ASK_ALWAYS, Level.fromPreferenceValue(null));
    }

    @Test
    public void testFromPreferenceValueUnknownFallsBackToAskAlways()
    {
        assertEquals(Level.ASK_ALWAYS, Level.fromPreferenceValue("bogus"));
    }

    @Test
    public void testPreferenceValueRoundTrip()
    {
        for (Level level : Level.values())
        {
            assertEquals(level, Level.fromPreferenceValue(level.getPreferenceValue()));
        }
    }
    @Test
    public void missingStoreStillRequiresReview() throws Exception
    {
        var field = com.ditrix.edt.mcp.server.Activator.class.getDeclaredField("plugin");
        field.setAccessible(true);
        Object saved = field.get(null);
        try
        {
            field.set(null, null);
            assertTrue(ConsentSettingsService.getInstance().isBslWriteReviewEnabled());
        }
        finally { field.set(null, saved); }
    }

    @Test
    public void upgradesDefaultOnAndExplicitOptOutIsIndependentOfDestructiveConsent() throws Exception
    {
        var store = new org.eclipse.jface.preference.PreferenceStore();
        var plugin = mock(com.ditrix.edt.mcp.server.Activator.class);
        when(plugin.getPreferenceStore()).thenReturn(store);
        var field = com.ditrix.edt.mcp.server.Activator.class.getDeclaredField("plugin");
        field.setAccessible(true);
        Object saved = field.get(null);
        try
        {
            field.set(null, plugin);
            var service = ConsentSettingsService.getInstance();
            assertTrue(service.isBslWriteReviewEnabled());
            new PreferenceInitializer().initializeDefaultPreferences();
            service.setLevel(Level.ALLOW_ALL);
            assertTrue(service.isBslWriteReviewEnabled());
            service.setBslWriteReviewEnabled(false);
            assertFalse(service.isBslWriteReviewEnabled());
            assertEquals(Level.ALLOW_ALL, service.getLevel());
            store.setToDefault(PreferenceConstants.PREF_BSL_WRITE_REVIEW_ENABLED);
            assertTrue(service.isBslWriteReviewEnabled());
        }
        finally { field.set(null, saved); }
    }

}
