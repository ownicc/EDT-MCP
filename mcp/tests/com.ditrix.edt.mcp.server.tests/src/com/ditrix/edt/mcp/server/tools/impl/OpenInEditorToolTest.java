package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.*;

import java.util.Map;

import org.eclipse.jface.text.Document;
import org.junit.Test;

import com.ditrix.edt.mcp.server.preferences.ToolPreset;
import com.ditrix.edt.mcp.server.tools.BuiltInToolRegistrar;
import com.ditrix.edt.mcp.server.tools.McpToolRegistry;

/** Navigation uses the editor document, never a saved-file line number. */
public class OpenInEditorToolTest
{
    @Test
    public void registeredToolRejectsConflictingNavigationBeforeOpeningAnything()
    {
        McpToolRegistry registry = McpToolRegistry.getInstance();
        try
        {
            BuiltInToolRegistrar.registerAll(registry);
            assertNotNull(registry.getTool("open_in_editor"));
            String result = registry.getTool("open_in_editor").execute(Map.of(
                "projectName", "P", "modulePath", "CommonModules/M/Module.bsl",
                "methodName", "Run", "line", "2"));
            assertTrue(result, result.contains("\"success\":false"));
            assertTrue(result, result.contains("methodName"));
            assertTrue(ToolPreset.CODE_REVIEW.getDisabledTools().contains("open_in_editor"));
            assertTrue(ToolPreset.ANALYSIS_ONLY.getDisabledTools().contains("open_in_editor"));
        }
        finally
        {
            registry.clear();
        }
    }

    @Test
    public void metadataSelectorsReachProjectValidationForEnglishAndRussianTypes()
    {
        for (Map<String, String> target : java.util.List.of(
            Map.of("objectName", "CommonModule.Calc"),
            Map.of("objectName", "ОбщийМодуль.Вычисления"),
            Map.of("objectName", "Document.Order"),
            Map.of("objectName", "Документ.Заказ"),
            Map.of("objectName", "Catalog.Products", "moduleType", "ManagerModule"),
            Map.of("objectName", "РегистрСведений.Цены", "moduleType", "RecordSetModule"),
            Map.of("objectName", "Document.Order", "moduleType", "FormModule", "formName", "ItemForm"),
            Map.of("objectName", "Документ.Заказ", "moduleType", "CommandModule", "commandName", "Печать"),
            Map.of("objectName", "CommonForm.Options"),
            Map.of("objectName", "ОбщаяФорма.Настройки", "moduleType", "FormModule"),
            Map.of("objectName", "CommonCommand.Print"),
            Map.of("objectName", "ОбщаяКоманда.Печать", "moduleType", "CommandModule"),
            Map.of("objectName", "WebService.Api"),
            Map.of("objectName", "HTTPСервис.Api")))
        {
            String result = executeTarget(target);
            assertTrue(target + ": " + result, result.contains("Project not found"));
        }
    }

    @Test
    public void objectAddressesUseTheExistingCanonicalModulePaths()
    {
        for (String type : java.util.List.of("CommonModule", "ОбщийМодуль"))
        {
            assertResolvedPath("CommonModules/Вычисления/Module.bsl", type + ".Вычисления", null, null, null);
        }
        for (String type : java.util.List.of("Document", "Документ"))
        {
            assertResolvedPath("Documents/Заказ/ObjectModule.bsl", type + ".Заказ", null, null, null);
            assertResolvedPath("Documents/Заказ/ManagerModule.bsl", type + ".Заказ", "ManagerModule", null, null);
            assertResolvedPath("Documents/Заказ/Forms/Форма/Module.bsl", type + ".Заказ", "FormModule", "Форма", null);
            assertResolvedPath("Documents/Заказ/Commands/Печать/CommandModule.bsl", type + ".Заказ", "CommandModule", null, "Печать");
        }
        for (String type : java.util.List.of("InformationRegister", "РегистрСведений"))
        {
            assertResolvedPath("InformationRegisters/Цены/RecordSetModule.bsl", type + ".Цены", "RecordSetModule", null, null);
        }
        for (String type : java.util.List.of("CommonForm", "ОбщаяФорма"))
        {
            assertResolvedPath("CommonForms/Настройки/Module.bsl", type + ".Настройки", null, null, null);
            assertResolvedPath("CommonForms/Настройки/Module.bsl", type + ".Настройки", "FormModule", null, null);
        }
        for (String type : java.util.List.of("CommonCommand", "ОбщаяКоманда"))
        {
            assertResolvedPath("CommonCommands/Печать/CommandModule.bsl", type + ".Печать", null, null, null);
        }
        assertResolvedPath("WebServices/Api/Module.bsl", "WebService.Api", null, null, null);
        assertResolvedPath("HTTPServices/Api/Module.bsl", "HTTPСервис.Api", null, null, null);
    }

    private static void assertResolvedPath(String expected, String objectName, String moduleType, String formName, String commandName)
    {
        var resolved = com.ditrix.edt.mcp.server.utils.ModuleTargetResolver.resolve(null, objectName, moduleType, formName, commandName);
        assertNull(resolved.error(), resolved.error());
        assertEquals(expected, resolved.modulePath());
    }

    @Test
    public void exactlyOneModuleSelectorIsRequired()
    {
        assertTargetError(Map.of(), "either modulePath or objectName is required");
        assertTargetError(Map.of("modulePath", "Module.bsl", "objectName", "CommonModule.Calc"), "mutually exclusive");
        assertTargetError(Map.of("modulePath", "Module.bsl", "moduleType", "Module"), "moduleType applies only with objectName");
    }

    @Test
    public void metadataSelectorsPreserveResolverValidation()
    {
        assertTargetError(Map.of("objectName", "NoDot"), "must be in format");
        assertTargetError(Map.of("objectName", "UnknownType.Name"), "unknown metadata type");
        assertTargetError(Map.of("objectName", "Document.Order", "moduleType", "UnknownModule"), "unknown moduleType");
        assertTargetError(Map.of("objectName", "Document.Order", "moduleType", "FormModule"), "formName is required");
        assertTargetError(Map.of("objectName", "Document.Order", "moduleType", "CommandModule"), "commandName is required");
        assertTargetError(Map.of("objectName", "Document.Order", "line", "1", "methodName", "Run"), "methodName");
    }

    @Test
    public void allSelectorsRetainProjectPathConfinement()
    {
        assertTargetError(Map.of("modulePath", "../Module.bsl"), "project-relative");
        assertTargetError(Map.of("modulePath", "/tmp/Module.bsl"), "project-relative");
        assertTargetError(Map.of("objectName", "CommonModule.../../Outside"), "project-relative");
        assertTargetError(Map.of("objectName", "Document.Order", "moduleType", "FormModule", "formName", "../../Outside"), "project-relative");
        assertTargetError(Map.of("objectName", "Document.Order", "moduleType", "CommandModule", "commandName", "../../Outside"), "project-relative");
        assertTrue(executeTarget(Map.of("modulePath", "CommonModules\\Calc\\Module.BSL")).contains("Project not found"));
        assertTrue(executeTarget(Map.of("modulePath", "Configuration/ManagedApplicationModule.bsl")).contains("Project not found"));
    }

    @Test
    public void schemaDeclaresAlternateAddressingAndRequiresOnlyProjectName()
    {
        var schema = com.google.gson.JsonParser.parseString(new OpenInEditorTool().getInputSchema()).getAsJsonObject();
        assertEquals("[\"projectName\"]", schema.getAsJsonArray("required").toString());
        for (String name : java.util.List.of("modulePath", "objectName", "moduleType", "formName", "commandName"))
        {
            assertTrue(name, schema.getAsJsonObject("properties").has(name));
        }
    }

    private static String executeTarget(Map<String, String> target)
    {
        var args = new java.util.HashMap<>(target);
        args.put("projectName", "MissingOpenEditorProject");
        return new OpenInEditorTool().execute(args);
    }

    private static void assertTargetError(Map<String, String> target, String message)
    {
        String result = executeTarget(target);
        assertTrue(result, result.contains("\"success\":false"));
        assertTrue(result, result.contains(message));
    }

    @Test
    public void noNavigationRetainsTheExistingEditorSelection() throws Exception
    {
        assertNull(OpenInEditorTool.locate(new Document("text"), null, null, null));
    }

    @Test
    public void coordinatesRespectCrLfEmptyLinesAndTheEndOfTheSelectedLine() throws Exception
    {
        Document doc = new Document("abc\r\n\r\nxyz");
        assertEquals(5, OpenInEditorTool.locate(doc, null, 2, 1).offset());
        assertEquals(10, OpenInEditorTool.locate(doc, null, 3, 4).offset());
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, null, 1, 5));
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, null, 4, 1));
    }

    @Test
    public void methodNavigationUsesCurrentUnsavedDocumentAndIgnoresComments() throws Exception
    {
        Document doc = new Document("Procedure Run()\nEndProcedure");
        doc.set("// unsaved line\n// Procedure Run()\n&НаСервере\nПроцедура Run()\nКонецПроцедуры");
        String before = doc.get();
        OpenInEditorTool.Position pos = OpenInEditorTool.locate(doc, "rUn", null, null);
        assertEquals(4, pos.line());
        assertEquals(doc.getLineOffset(3), pos.offset());
        assertEquals(before, doc.get());
    }

    @Test
    public void absentOrAmbiguousMethodIsRefused() throws Exception
    {
        Document doc = new Document("Procedure Run()\nEndProcedure\nProcedure RUN()\nEndProcedure");
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, "Run", null, null));
        assertThrows(IllegalArgumentException.class, () -> OpenInEditorTool.locate(doc, "Missing", null, null));
    }

    @Test
    public void invalidOrConflictingCoordinatesAreRefusedBeforeUiWork()
    {
        OpenInEditorTool tool = new OpenInEditorTool();
        for (Map<String, String> bad : java.util.List.of(
            Map.of("line", "0"), Map.of("line", "-1"), Map.of("line", "1.5"),
            Map.of("line", "1", "column", "0"), Map.of("column", "2"),
            Map.of("methodName", "Run", "column", "1")))
        {
            java.util.HashMap<String, String> args = new java.util.HashMap<>(bad);
            args.put("projectName", "P");
            args.put("modulePath", "CommonModules/M/Module.bsl");
            String result = tool.execute(args);
            assertTrue(result, result.contains("\"success\":false"));
        }
        assertTrue(tool.execute(Map.of()).contains("projectName"));
        assertTrue(tool.execute(Map.of("projectName", "P")).contains("modulePath"));
        assertTrue(tool.execute(Map.of("projectName", "P", "modulePath", "X.mdo")).contains(".bsl"));
    }

    @Test
    public void splitDeclarationCannotHideADuplicateFromMethodLookup() throws Exception
    {
        Document doc = new Document("#If Server Then\nProcedure Run()\nEndProcedure\n#Else\nProcedure Run\n()\nEndProcedure\n#EndIf");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> OpenInEditorTool.locate(doc, "Run", null, null));
        assertTrue(error.getMessage(), error.getMessage().contains("line"));
    }
}
