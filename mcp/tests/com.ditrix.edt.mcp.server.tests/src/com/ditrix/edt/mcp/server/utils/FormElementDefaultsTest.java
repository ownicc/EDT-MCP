// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.EAttribute;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.junit.Test;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.mcore.McoreFactory;
import com._1c.g5.v8.dt.platform.version.Version;

/** Designer defaults on the real form metamodel, including newly replaced ext-info. */
public class FormElementDefaultsTest
{
    @Test
    public void usualGroupIsUnitedAndHasDesignerLayout() throws Exception
    {
        EObject form = form();
        EObject group = group(form, "Main", null, null); //$NON-NLS-1$
        EObject ext = ext(group);
        assertValue(ext, "united", true); //$NON-NLS-1$
        assertValue(ext, "showLeftMargin", true); //$NON-NLS-1$
        assertValue(ext, "showTitle", true); //$NON-NLS-1$
        assertValue(ext, "group", "HorizontalIfPossible"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext, "representation", "WeakSeparation"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext, "throughAlign", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext, "currentRowUse", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext, "behavior", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void pagesShowTabsAndNestedPageShowsItsTitle() throws Exception
    {
        EObject form = form();
        EObject pages = group(form, "Tabs", null, "Pages"); //$NON-NLS-1$ //$NON-NLS-2$
        EObject page = group(form, "First", "Tabs", null); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(pages), "pagesRepresentation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(pages), "currentRowUse", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(page, "type", "Page"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(page), "showTitle", true); //$NON-NLS-1$
        assertValue(ext(page), "group", "Vertical"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void otherGroupsHaveDesignerDefaults() throws Exception
    {
        EObject form = form();
        EObject column = group(form, "Columns", null, "ColumnGroup"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(column), "group", "Vertical"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(column), "showTitle", true); //$NON-NLS-1$
        assertValue(ext(group(form, "Bar", null, "CommandBar")), "horizontalAlign", "Left"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertValue(ext(group(form, "Menu", null, "Popup")), "representation", "Auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

    @Test
    public void changingFieldKindBackToInputRestoresEditingDefaults() throws Exception
    {
        EObject form = form();
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.ATTRIBUTE,
            "Value", null, null, null, null, false, null)); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.FIELD,
            "Input", null, "Value", null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject field = FormElementWriter.findFormItem(form, "Input"); //$NON-NLS-1$
        setKind(field, "LabelField"); //$NON-NLS-1$
        FormElementWriter.syncItemExtInfo(form, field, new ArrayList<>());
        setKind(field, "InputField"); //$NON-NLS-1$
        FormElementWriter.syncItemExtInfo(form, field, new ArrayList<>());
        for (String name : new String[] {"wrap", "chooseType", "typeDomainEnabled", "textEdit"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        {
            assertValue(ext(field), name, true);
        }
    }

    @Test
    public void version851UsesAutomaticGroupTitlesAndLayout() throws Exception
    {
        EObject form = form();
        for (String type : new String[] {"UsualGroup", "Page", "ColumnGroup"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        {
            EObject group = group(form, type, null, type, Version.V8_5_1);
            assertValue(ext(group), "showTitle851", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
            assertValue(ext(group), "showTitle", false); //$NON-NLS-1$
            assertValue(ext(group), "group", "ColumnGroup".equals(type) ? "Vertical" : "Auto"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        }
        assertValue(ext(FormElementWriter.findFormItem(form, "ColumnGroup")), "showInCard", true); //$NON-NLS-1$ //$NON-NLS-2$
        EObject popup = group(form, "Menu", null, "Popup", Version.V8_5_1); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(popup), "importance", "Normal"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void version851InputHasNormalTextOnCreationAndAfterRetyping() throws Exception
    {
        EObject form = form();
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.ATTRIBUTE,
            "Value", null, null, null, null, false, null)); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.FIELD,
            "Input", null, "Value", null, null, false, null, null, null, Version.V8_5_1)); //$NON-NLS-1$ //$NON-NLS-2$
        EObject field = FormElementWriter.findFormItem(form, "Input"); //$NON-NLS-1$
        assertValue(ext(field), "textSize", "Normal"); //$NON-NLS-1$ //$NON-NLS-2$
        setKind(field, "LabelField"); //$NON-NLS-1$
        FormElementWriter.syncItemExtInfo(form, field, new ArrayList<>(), Version.V8_5_1);
        setKind(field, "InputField"); //$NON-NLS-1$
        FormElementWriter.syncItemExtInfo(form, field, new ArrayList<>(), Version.V8_5_1);
        assertValue(ext(field), "textSize", "Normal"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(field), "textEdit", true); //$NON-NLS-1$
    }

    @Test
    public void ensureInitializesNewExtensionButKeepsExistingUserSettings() throws Exception
    {
        EObject form = form();
        EObject group = group(form, "Main", null, null, Version.V8_5_1); //$NON-NLS-1$
        group.eSet(group.eClass().getEStructuralFeature("extInfo"), null); //$NON-NLS-1$
        EObject created = FormElementWriter.ensureExtInfo(form, group, Version.V8_5_1);
        assertValue(created, "showTitle851", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(created, "united", true); //$NON-NLS-1$
        created.eSet(created.eClass().getEStructuralFeature("united"), false); //$NON-NLS-1$
        assertSame(created, FormElementWriter.ensureExtInfo(form, group, Version.V8_5_1));
        FormElementWriter.syncItemExtInfo(form, group, new ArrayList<>(), Version.V8_5_1);
        assertSame(created, ext(group));
        assertValue(created, "united", false); //$NON-NLS-1$
    }

    @Test
    public void retypingGroupKeepsExplicitSharedLayout() throws Exception
    {
        EObject form = form();
        EObject group = group(form, "Main", null, null, Version.V8_5_1); //$NON-NLS-1$
        EObject oldExt = ext(group);
        EStructuralFeature grouping = oldExt.eClass().getEStructuralFeature("group"); //$NON-NLS-1$
        oldExt.eSet(grouping, grouping.getEType().getEPackage().getEFactoryInstance()
            .createFromString((org.eclipse.emf.ecore.EDataType)grouping.getEType(), "Vertical")); //$NON-NLS-1$
        setKind(group, "Page"); //$NON-NLS-1$
        FormElementWriter.syncItemExtInfo(form, group, new ArrayList<>(), Version.V8_5_1);
        assertValue(ext(group), "group", "Vertical"); //$NON-NLS-1$ //$NON-NLS-2$
        assertValue(ext(group), "showTitle851", "auto"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @SuppressWarnings("unchecked")
    public void generatedTableColumnsUseVersion851TextSize() throws Exception
    {
        EObject form = form();
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.ATTRIBUTE,
            "Rows", null, null, null, null, false, null)); //$NON-NLS-1$
        EObject attribute = FormElementWriter.findFormAttribute(form, "Rows"); //$NON-NLS-1$
        EObject description = (EObject)attribute.eGet(attribute.eClass().getEStructuralFeature("valueType")); //$NON-NLS-1$
        com._1c.g5.v8.dt.mcore.Type valueTable = McoreFactory.eINSTANCE.createType();
        valueTable.setName("ValueTable"); //$NON-NLS-1$
        ((List<EObject>)description.eGet(description.eClass().getEStructuralFeature("types"))).add(valueTable); //$NON-NLS-1$
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.COLUMN,
            "Caption", "Rows", null, null, null, false, null)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(FormElementWriter.createTable(form, "Table", null, "Rows", List.of(), //$NON-NLS-1$ //$NON-NLS-2$
            null, null, false, null, Version.V8_5_1));
        EObject column = FormElementWriter.findFormItem(form, "TableCaption"); //$NON-NLS-1$
        assertNotNull(column);
        assertValue(ext(column), "textSize", "Normal"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void generatedObjectFormFieldsUseVersion851TextSize()
    {
        EObject form = FormElementWriter.createContentForm(null, null, Version.V8_5_1, false,
            true, "Catalog", List.of("Code")); //$NON-NLS-1$ //$NON-NLS-2$
        EObject field = FormElementWriter.findFormItem(form, "Code"); //$NON-NLS-1$
        assertNotNull(field);
        assertValue(ext(field), "textSize", "Normal"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void newGroupExtensionsMatchDesignerFactoryAcrossVersionBoundaries() throws Exception
    {
        Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.form"); //$NON-NLS-1$
        String factoryClass = "com._1c.g5.v8.dt.form.FormObjectFactory"; //$NON-NLS-1$
        Class<?> factoryType = bundle == null ? Class.forName(factoryClass) : bundle.loadClass(factoryClass);
        IModelObjectFactory factory = (IModelObjectFactory)factoryType.getConstructor().newInstance();
        for (Version version : new Version[] {Version.V8_3_19, Version.V8_3_20, Version.V8_3_27, Version.V8_5_1})
        {
            EObject form = form();
            for (String type : new String[] {"UsualGroup", "Page", "Pages", "ColumnGroup", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "CommandBar", "Popup", "ButtonGroup"}) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            {
                EObject actual = ext(group(form, type, null, type, version));
                EObject expected = factory.create(actual.eClass(), form, version);
                for (EAttribute property : actual.eClass().getEAllAttributes())
                {
                    if (!property.isDerived() && !property.isTransient())
                    {
                        assertEquals(version + ": " + type + "." + property.getName(), //$NON-NLS-1$ //$NON-NLS-2$
                            expected.eGet(property), actual.eGet(property));
                    }
                }
            }
        }
    }

    private static EObject form() throws Exception
    {
        Class<?> model = Class.forName("com._1c.g5.v8.dt.form.model.FormPackage"); //$NON-NLS-1$
        EPackage pkg = (EPackage)model.getField("eINSTANCE").get(null); //$NON-NLS-1$
        return pkg.getEFactoryInstance().create((EClass)pkg.getEClassifier("Form")); //$NON-NLS-1$
    }

    private static EObject group(EObject form, String name, String parent, String type)
    {
        return group(form, name, parent, type, null);
    }

    private static EObject group(EObject form, String name, String parent, String type, Version version)
    {
        assertNull(FormElementWriter.createMember(form, FormElementWriter.Kind.GROUP,
            name, parent, type, "en", name, false, null, null, null, version)); //$NON-NLS-1$
        return FormElementWriter.findFormItem(form, name);
    }

    private static EObject ext(EObject item)
    {
        return (EObject)item.eGet(item.eClass().getEStructuralFeature("extInfo")); //$NON-NLS-1$
    }

    private static void setKind(EObject item, String kind)
    {
        EStructuralFeature type = item.eClass().getEStructuralFeature("type"); //$NON-NLS-1$
        item.eSet(type, type.getEType().getEPackage().getEFactoryInstance()
            .createFromString((org.eclipse.emf.ecore.EDataType)type.getEType(), kind));
    }

    private static void assertValue(EObject item, String name, Object expected)
    {
        EStructuralFeature feature = item.eClass().getEStructuralFeature(name);
        assertNotNull(name, feature);
        Object actual = item.eGet(feature);
        assertEquals(item.eClass().getName() + "." + name, expected.toString(), actual.toString()); //$NON-NLS-1$
    }
}
