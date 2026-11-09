/**
 * MCP Server for EDT
 * Licensed under AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.utils;

import com.ditrix.edt.mcp.server.protocol.ToolResult;

/** Shared read-only module addressing used by write and editor tools. */
public final class ModuleTargetResolver
{
    private static final String MODULE_TYPE_MODULE = "Module"; //$NON-NLS-1$
    private static final String MODULE_TYPE_OBJECT_MODULE = "ObjectModule"; //$NON-NLS-1$
    private static final String MODULE_TYPE_COMMAND_MODULE = "CommandModule"; //$NON-NLS-1$
    private static final String MODULE_FILE_SUFFIX = "/Module.bsl"; //$NON-NLS-1$

    private ModuleTargetResolver() { }

    /** Either a ready error JSON payload or the resolved source-relative path. */
    public record Result(String error, String modulePath) { }

    /** Resolves exactly one selector; callers retain their existing path policies. */
    public static Result resolve(String modulePath, String objectName,
        String moduleType, String formName, String commandName)
    {
        boolean hasModulePath = modulePath != null && !modulePath.isEmpty();
        boolean hasObjectName = objectName != null && !objectName.isEmpty();
        boolean hasModuleType = moduleType != null && !moduleType.isEmpty();

        if (hasModulePath && hasObjectName)
        {
            return new Result(ToolResult.error("modulePath and objectName are mutually exclusive; " + //$NON-NLS-1$
                "pass only one (modulePath for a direct path, or objectName to resolve it)").toJson(), null); //$NON-NLS-1$
        }
        if (!hasModulePath && !hasObjectName)
        {
            return new Result(
                ToolResult.error("either modulePath or objectName is required").toJson(), null); //$NON-NLS-1$
        }
        if (hasModulePath && hasModuleType)
        {
            return new Result(ToolResult.error("moduleType applies only with objectName; " + //$NON-NLS-1$
                "it is meaningless with an explicit modulePath, so drop one of them").toJson(), null); //$NON-NLS-1$
        }

        if (!hasModulePath)
        {
            String resolved = resolveModulePath(objectName, moduleType, formName, commandName);
            if (isErrorJson(resolved))
            {
                // resolveModulePath signals failure by returning a ready
                // ToolResult.error(...).toJson() payload; surface it as-is.
                return new Result(resolved, null);
            }
            modulePath = resolved;
        }

        return new Result(null, modulePath);
    }

    /** Preserves the write tool's existing path validation and error messages. */
    public static Result resolveForWrite(String modulePath, String objectName,
        String moduleType, String formName, String commandName)
    {
        Result result = resolve(modulePath, objectName, moduleType, formName, commandName);
        if (result.error() != null)
        {
            return result;
        }
        modulePath = result.modulePath();
        // Validate modulePath: prevent path traversal
        if (modulePath.contains("..")) //$NON-NLS-1$
        {
            return new Result(ToolResult.error("modulePath must not contain '..'").toJson(), null); //$NON-NLS-1$
        }

        // Validate modulePath: only .bsl files allowed
        if (!modulePath.endsWith(".bsl")) //$NON-NLS-1$
        {
            return new Result(
                ToolResult.error("only .bsl module files can be written").toJson(), null); //$NON-NLS-1$
        }

        return new Result(null, modulePath);
    }

    /**
     * Returns {@code true} when the given resolver result is a structured error
     * payload (a {@link ToolResult#error} JSON object) rather than a resolved
     * module path. A resolved path is a plain {@code src/}-relative string, so it
     * can never begin with the JSON object opener.
     */
    private static boolean isErrorJson(String resolverResult)
    {
        return resolverResult.startsWith("{"); //$NON-NLS-1$
    }

    /**
     * Resolves objectName + moduleType to a module file path relative to src/.
     * On success returns the plain path; on failure returns a ready
     * {@link ToolResult#error} JSON payload (detected by {@link #isErrorJson})
     * so the error reaches the client through the structured error contract
     * rather than a bare "Error:" string.
     */
    private static String resolveModulePath(String objectName, String moduleType,
        String formName, String commandName)
    {
        // Parse objectName: "Document.MyDoc" -> typePart="Document", namePart="MyDoc"
        int dotIndex = objectName.indexOf('.'); //$NON-NLS-1$
        if (dotIndex <= 0 || dotIndex >= objectName.length() - 1)
        {
            return ToolResult.error("objectName must be in format 'Type.Name' " + //$NON-NLS-1$
                "(e.g. 'Document.MyDoc', 'CommonModule.MyModule')").toJson(); //$NON-NLS-1$
        }

        String typePart = objectName.substring(0, dotIndex);
        String namePart = objectName.substring(dotIndex + 1);

        // Resolve type to English singular
        String englishType = MetadataTypeUtils.toEnglishSingular(typePart);
        if (englishType == null)
        {
            return ToolResult.error("unknown metadata type: " + typePart).toJson(); //$NON-NLS-1$
        }

        // Get directory name
        String dirName = MetadataTypeUtils.getDirectoryName(typePart);
        if (dirName == null)
        {
            return ToolResult.error("metadata type '" + typePart + "' has no source directory").toJson(); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Determine default moduleType based on metadata type
        moduleType = resolveDefaultModuleType(moduleType, englishType);

        // Build path based on moduleType
        switch (moduleType)
        {
            case MODULE_TYPE_MODULE:
                return dirName + "/" + namePart + MODULE_FILE_SUFFIX; //$NON-NLS-1$

            case MODULE_TYPE_OBJECT_MODULE:
                return dirName + "/" + namePart + "/ObjectModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "ManagerModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/ManagerModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "RecordSetModule": //$NON-NLS-1$
                return dirName + "/" + namePart + "/RecordSetModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$

            case "FormModule": //$NON-NLS-1$
                // CommonForms don't need formName — path is always Module.bsl
                if ("CommonForm".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + MODULE_FILE_SUFFIX; //$NON-NLS-1$
                }
                if (formName == null || formName.isEmpty())
                {
                    return ToolResult.error("formName is required when moduleType=FormModule").toJson(); //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Forms/" + formName + MODULE_FILE_SUFFIX; //$NON-NLS-1$ //$NON-NLS-2$

            case MODULE_TYPE_COMMAND_MODULE:
                // CommonCommands don't need commandName — path is always CommandModule.bsl
                if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
                {
                    return dirName + "/" + namePart + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$
                }
                if (commandName == null || commandName.isEmpty())
                {
                    return ToolResult.error("commandName is required when moduleType=CommandModule").toJson(); //$NON-NLS-1$
                }
                return dirName + "/" + namePart + "/Commands/" + commandName + "/CommandModule.bsl"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            default:
                return ToolResult.error("unknown moduleType: " + moduleType + //$NON-NLS-1$
                    ". Allowed: ObjectModule, ManagerModule, FormModule, " + //$NON-NLS-1$
                    "CommandModule, RecordSetModule, Module").toJson(); //$NON-NLS-1$
        }
    }

    /**
     * Returns the supplied {@code moduleType} unchanged when it is non-empty,
     * otherwise derives the default module type from the English metadata type:
     * {@code Module} for module-bearing singletons (CommonModule, CommonForm,
     * WebService, HTTPService), {@code CommandModule} for CommonCommand, and
     * {@code ObjectModule} for everything else.
     *
     * @param moduleType the requested module type (may be {@code null} or empty)
     * @param englishType the resolved English singular metadata type
     * @return the explicit module type, or the derived default when none was given
     */
    private static String resolveDefaultModuleType(String moduleType, String englishType)
    {
        if (moduleType != null && !moduleType.isEmpty())
        {
            return moduleType;
        }
        if ("CommonModule".equals(englishType) //$NON-NLS-1$
            || "CommonForm".equals(englishType) //$NON-NLS-1$
            || "WebService".equals(englishType) //$NON-NLS-1$
            || "HTTPService".equals(englishType)) //$NON-NLS-1$
        {
            return MODULE_TYPE_MODULE;
        }
        if ("CommonCommand".equals(englishType)) //$NON-NLS-1$
        {
            return MODULE_TYPE_COMMAND_MODULE;
        }
        return MODULE_TYPE_OBJECT_MODULE;
    }

}
