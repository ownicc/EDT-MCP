# BSL diff preference

Enable the existing method-aware BSL review viewer from MCP Server → General,
without editing the EDT launcher or restarting. The user chose manual opt-in:
the new checkbox starts unchecked for both new and existing workspaces.

## Approaches

A saved Eclipse preference reuses the existing settings lifecycle and affects
the next review opened. This is the selected approach. Editing the launcher
would require a restart and platform-specific installation access. Enabling the
viewer automatically after an update was explicitly declined by the user.

The checkbox appears next to BSL write review, with English and Russian labels.
Apply/OK saves it; Restore Defaults clears it. Existing review tabs retain their
viewer. The legacy explicit `-Dedt.mcp.bslDiff=jgit` override remains supported
and takes precedence, as it does today. Existing Difftastic selection and error
fallbacks remain intact. Only MCP review windows use this preference.
