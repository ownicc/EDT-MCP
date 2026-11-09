package com.ditrix.edt.mcp.server.preferences;

import static org.junit.Assert.assertEquals;

import java.util.Map;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

/** Existing read-only profiles must not acquire a new UI mutation on upgrade. */
public class OpenInEditorMigrationTest
{
    @Test
    public void version22ProfilesKeepTheirRecordedUiPolicy()
    {
        Map<String, Boolean> cases = Map.of("analysis-only", true, "code-review", true,
            "custom", false, "development", false, "all-tools", false);
        for (var entry : cases.entrySet())
        {
            PreferenceStore store = new PreferenceStore();
            store.setValue(PreferenceConstants.PREF_DISABLED_TOOLS, "write_module_source");
            store.setValue(PreferenceConstants.PREF_TOOL_PRESET_ID, entry.getKey());
            store.setValue(PreferenceConstants.PREF_TOOL_PREFS_MIGRATION, 22);
            ToolSettingsService.ensureMigratedForTest(store);
            assertEquals(entry.getKey(), entry.getValue(), Boolean.valueOf(
                ToolSettingsService.parseDisabledTools(store.getString(PreferenceConstants.PREF_DISABLED_TOOLS))
                    .contains("open_in_editor")));
            assertEquals(entry.getKey(), store.getString(PreferenceConstants.PREF_TOOL_PRESET_ID));
        }
    }
}
