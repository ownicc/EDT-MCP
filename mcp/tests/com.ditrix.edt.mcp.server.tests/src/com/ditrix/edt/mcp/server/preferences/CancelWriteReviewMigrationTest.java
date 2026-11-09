package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Set;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

public class CancelWriteReviewMigrationTest
{
    @Test public void newReadOnlyPresetsExcludeWithdrawalButRetainPolling()
    {
        for (ToolPreset preset : List.of(ToolPreset.ANALYSIS_ONLY, ToolPreset.CODE_REVIEW))
        {
            assertTrue(preset.getId(), preset.getDisabledTools().contains("cancel_write_review"));
            assertFalse(preset.getId(), preset.getDisabledTools().contains("get_write_review_status"));
        }
    }

    @Test public void version23ProfilesGainOnlyTheirNewReadOnlyObligation()
    {
        for (String preset : List.of("analysis-only", "code-review", "development", "all-tools", "custom", ""))
        {
            PreferenceStore store = new PreferenceStore();
            store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled");
            if (!preset.isEmpty()) store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, preset);
            store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 23);
            ToolSettingsService.ensureMigratedForTest(store);
            Set<String> expected = Set.of("analysis-only", "code-review").contains(preset)
                ? Set.of("owner_disabled", "cancel_write_review") : Set.of("owner_disabled");
            assertEquals(preset, expected, ToolSettingsService.parseDisabledTools(
                store.getString(PreferenceConstants.PREF_DISABLED_TOOLS)));
            assertEquals(preset, store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
            assertEquals(24, store.getInt(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION));
        }
    }

    @Test public void laterReadsPreserveDeliberateWithdrawalOptIn()
    {
        PreferenceStore store = new PreferenceStore();
        store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "owner_disabled");
        store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, "code-review");
        store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 24);
        ToolSettingsService.ensureMigratedForTest(store);
        assertEquals("owner_disabled", store.getString(PreferenceConstants.PREF_DISABLED_TOOLS));
    }
}
