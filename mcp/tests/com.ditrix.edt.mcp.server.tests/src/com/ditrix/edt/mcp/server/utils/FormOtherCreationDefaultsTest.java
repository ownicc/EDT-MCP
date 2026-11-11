// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static com.ditrix.edt.mcp.server.utils.FormItemExtensionDefaultsTest.*;
import static org.junit.Assert.*;

import java.util.List;

import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.mcore.Type;
import com._1c.g5.v8.dt.mcore.TypeDescription;
import com._1c.g5.v8.dt.platform.version.Version;

/** Defaults affecting command behavior, dynamic lists and document posting. */
@SuppressWarnings("nls")
public class FormOtherCreationDefaultsTest
{
    @Test
    public void commandButtonAndTableMatchNativeVersionedDefaults() throws Exception
    {
        for (Version version : new Version[] {Version.V8_3_27, Version.V8_5_1})
        {
            EObject form = model("Form");
            create(form, FormElementWriter.Kind.COMMAND, "Run", null, version);
            EObject command = FormElementWriter.findFormCommand(form, "Run");
            EObject expected = nativeFactory().create(command.eClass(), form, version);
            assertProperty(expected, command, "selectedRowsUse");
            create(form, FormElementWriter.Kind.BUTTON, "Button", "Run", version);
            EObject button = FormElementWriter.findFormItem(form, "Button");
            expected = nativeFactory().create(button.eClass(), form, version);
            assertProperty(expected, button, "buttonImportance");
            create(form, FormElementWriter.Kind.ATTRIBUTE, "Rows", null, version);
            setType(FormElementWriter.findFormAttribute(form, "Rows"), "ValueTable");
            create(form, FormElementWriter.Kind.TABLE, "Table", "Rows", version);
            EObject table = FormElementWriter.findFormItem(form, "Table");
            expected = nativeFactory().create(table.eClass(), form, version);
            assertProperty(expected, table, "rowSelectionMode");
            assertProperty(expected, table, "autoMaxCardHeight");
        }
    }

    @Test
    public void documentFormStartsWithRepostAndKeepsExplicitFalse() throws Exception
    {
        for (Version version : new Version[] {Version.V8_3_27, Version.V8_5_1})
        {
            EObject form = model("Form");
            create(form, FormElementWriter.Kind.ATTRIBUTE, "Object", null, version);
            EObject attribute = FormElementWriter.findFormAttribute(form, "Object");
            setType(attribute, "DocumentObject.Invoice");
            set(attribute, "main", true);
            assertEquals("DocumentFormExtInfo", FormElementWriter.syncFormExtInfo(form));
            EObject ext = FormElementWriter.extInfoInstance(form);
            assertAttributes(nativeFactory().create(ext.eClass(), form, version), ext);
            set(ext, "repostOnWrite", false);
            FormElementWriter.syncFormExtInfo(form);
            assertSame(ext, FormElementWriter.extInfoInstance(form));
            assertEquals(false, get(ext, "repostOnWrite"));
        }
    }

    @Test
    public void generatedDocumentFormStartsWithRepost() throws Exception
    {
        com._1c.g5.v8.dt.metadata.mdclass.Document owner =
            com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory.eINSTANCE.createDocument();
        owner.setName("Invoice");
        EObject produced = newContained(owner, "producedTypes");
        com._1c.g5.v8.dt.metadata.mdtype.MdType objectType =
            (com._1c.g5.v8.dt.metadata.mdtype.MdType)newContained(produced, "objectType");
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName("DocumentObject.Invoice");
        objectType.setType(type);
        EObject form = FormElementWriter.createContentForm(null, owner, Version.V8_5_1, false,
            true, "Document", List.of("Number"));
        assertNotNull(FormElementWriter.extInfoInstance(form));
        assertEquals(true, get(FormElementWriter.extInfoInstance(form), "repostOnWrite"));
    }

    @Test
    public void dynamicListAttributesAndTablesMatchFactoryAndKeepEdits() throws Exception
    {
        for (Version version : new Version[] {Version.V8_3_20, Version.V8_3_21, Version.V8_3_27, Version.V8_5_1})
        {
            EObject form = model("Form");
            create(form, FormElementWriter.Kind.ATTRIBUTE, "List", null, version);
            EObject attribute = FormElementWriter.findFormAttribute(form, "List");
            // TypeDescription is supplied directly at this model boundary; public conversion is tested below.
            setType(attribute, "DynamicList");
            FormElementWriter.syncAttributeExtInfo(form, attribute);
            EObject ext = FormElementWriter.extInfoInstance(attribute);
            IModelObjectFactory factory = nativeFactory();
            assertAttributes(factory.create(ext.eClass(), attribute, version), ext);
            create(form, FormElementWriter.Kind.TABLE, "Table", "List", version);
            EObject table = FormElementWriter.findFormItem(form, "Table");
            EObject tableExt = FormElementWriter.extInfoInstance(table);
            EObject expected = factory.create(tableExt.eClass(), table, version);
            assertAttributes(expected, tableExt);
            assertTrue(EcoreUtil.equals((EObject)get(expected, "period"), (EObject)get(tableExt, "period")));
            assertTrue(EcoreUtil.equals((EObject)get(expected, "topLevelParent"),
                (EObject)get(tableExt, "topLevelParent")));
            set(ext, "autoSaveUserSettings", false);
            set(ext, "getInvisibleFieldPresentations", false);
            FormElementWriter.configureDynamicListQuery(form, attribute, "SELECT 1 AS Number", true,
                null, null, version);
            assertSame(ext, FormElementWriter.extInfoInstance(attribute));
            assertEquals(false, get(ext, "autoSaveUserSettings"));
            assertEquals(false, get(ext, "getInvisibleFieldPresentations"));
            set(tableExt, "showRoot", false);
            set(tableExt, "autoRefreshPeriod", 17);
            FormElementWriter.syncTableExtInfo(form, table);
            assertSame(tableExt, FormElementWriter.extInfoInstance(table));
            assertEquals(false, get(tableExt, "showRoot"));
            assertEquals(17, get(tableExt, "autoRefreshPeriod"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void publicQueryConversionGetsDefaultsAndPreservesExplicitProperties() throws Exception
    {
        com._1c.g5.v8.dt.platform.IEObjectProvider provider =
            org.mockito.Mockito.mock(com._1c.g5.v8.dt.platform.IEObjectProvider.class);
        Type dynamicList = McoreFactory.eINSTANCE.createType();
        dynamicList.setName("DynamicList");
        org.mockito.Mockito.when(provider.createProxy("DynamicList")).thenReturn(dynamicList);
        var registry = com._1c.g5.v8.dt.platform.IEObjectProvider.Registry.INSTANCE;
        var field = registry.getClass().getDeclaredField("providerByClass");
        field.setAccessible(true);
        Object previous = field.get(registry);
        var replacement = previous == null
            ? new java.util.HashMap<EClass, java.util.Map<Version, com._1c.g5.v8.dt.platform.IEObjectProvider>>()
            : new java.util.HashMap<>((java.util.Map<EClass,
                java.util.Map<Version, com._1c.g5.v8.dt.platform.IEObjectProvider>>)previous);
        replacement.put(com._1c.g5.v8.dt.mcore.McorePackage.Literals.TYPE_ITEM,
            java.util.Map.of(Version.V8_3_27, provider, Version.V8_5_1, provider));
        field.set(registry, replacement);
        try
        {
            for (Version version : new Version[] {Version.V8_3_27, Version.V8_5_1})
            {
                EObject form = model("Form");
                create(form, FormElementWriter.Kind.ATTRIBUTE, "List", null, version);
                EObject attribute = FormElementWriter.findFormAttribute(form, "List");
                FormElementWriter.configureDynamicListQuery(form, attribute, "SELECT 1 AS Number", true,
                    null, null, version);
                EObject ext = FormElementWriter.extInfoInstance(attribute);
                for (String flag : List.of("autoSaveUserSettings", "dynamicDataRead",
                    "autoFillAvailableFields", "getInvisibleFieldPresentations"))
                {
                    assertEquals(flag, true, get(ext, flag));
                }
                set(ext, "autoSaveUserSettings", false);
                set(ext, "getInvisibleFieldPresentations", false);
                FormElementWriter.configureDynamicListQuery(form, attribute, "SELECT 2 AS Number", true,
                    null, null, version);
                assertSame(ext, FormElementWriter.extInfoInstance(attribute));
                assertEquals(false, get(ext, "autoSaveUserSettings"));
                assertEquals(false, get(ext, "getInvisibleFieldPresentations"));
            }
        }
        finally
        {
            field.set(registry, previous);
        }
    }

    private static EObject newContained(EObject owner, String name)
    {
        EReference feature = (EReference)owner.eClass().getEStructuralFeature(name);
        EObject value = EcoreUtil.create(feature.getEReferenceType());
        owner.eSet(feature, value);
        return value;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tableRebindUsesProjectVersionForNewDynamicListHolder() throws Exception
    {
        for (Version version : new Version[] {Version.V8_3_20, Version.V8_3_21, Version.V8_5_1})
        {
            EObject form = model("Form");
            create(form, FormElementWriter.Kind.ATTRIBUTE, "List", null, version);
            setType(FormElementWriter.findFormAttribute(form, "List"), "DynamicList");
            FormElementWriter.syncAttributeExtInfo(form, FormElementWriter.findFormAttribute(form, "List"));
            EObject table = model("Table");
            set(table, "name", "Table");
            ((List<EObject>)get(form, "items")).add(table);
            FormElementWriter.DataPathPlan plan =
                FormElementWriter.planDataPathChange(form, table, "List", version);
            assertNull(plan.error());
            FormElementWriter.applyDataPathChange(form, table, plan);
            EObject actual = FormElementWriter.extInfoInstance(table);
            EObject expected = nativeFactory().create(actual.eClass(), table, version);
            assertAttributes(expected, actual);
        }
    }

    @Test
    public void pdfChromeParticipatesInItemLookupAndNameUniqueness() throws Exception
    {
        EObject form = model("Form");
        create(form, FormElementWriter.Kind.ATTRIBUTE, "Value", null, Version.V8_5_1);
        create(form, FormElementWriter.Kind.FIELD, "Pdf", "Value", Version.V8_5_1);
        EObject field = FormElementWriter.findFormItem(form, "Pdf");
        setEnum(field, "type", "PDFDocumentField");
        FormElementWriter.syncItemExtInfo(form, field, new java.util.ArrayList<>(), Version.V8_5_1);
        EObject status = (EObject)get(FormElementWriter.extInfoInstance(field), "viewStatusAddition");
        for (EObject item : List.of(status, (EObject)get(status, "contextMenu"),
            (EObject)get(status, "extendedTooltip")))
        {
            String name = get(item, "name").toString();
            assertSame(name, item, FormElementWriter.findFormItem(form, name));
            assertNotNull(FormElementWriter.createMember(form, FormElementWriter.Kind.DECORATION,
                name, null, null, "en", null, false, null));
        }
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        EClass formItem = (EClass)form.eClass().getEPackage().getEClassifier("FormItem");
        for (var it = form.eAllContents(); it.hasNext();)
        {
            EObject item = it.next();
            if (formItem.isInstance(item))
            {
                assertTrue("unique item id: " + get(item, "name"), ids.add((Integer)get(item, "id")));
            }
        }
    }

    static void setType(EObject attribute, String name)
    {
        TypeDescription description = McoreFactory.eINSTANCE.createTypeDescription();
        Type type = McoreFactory.eINSTANCE.createType();
        type.setName(name);
        description.getTypes().add(type);
        set(attribute, "valueType", description);
    }

    private static void assertProperty(EObject expected, EObject actual, String name)
    {
        assertEquals(actual.eClass().getName() + "." + name, get(expected, name), get(actual, name));
    }
}
