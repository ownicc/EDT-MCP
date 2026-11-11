// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.emf.ecore.*;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.osgi.framework.Bundle;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.platform.version.Version;

/** Real model comparisons with EDT, including kind changes and holder reuse (edt-a72). */
@RunWith(Parameterized.class)
@SuppressWarnings("nls")
public class FormItemExtensionDefaultsTest
{
    @Parameterized.Parameters(name = "{0}: {1}")
    public static Collection<Object[]> cases()
    {
        List<Object[]> cases = new ArrayList<>();
        String[][] fields = {
            {"InputField", "InputFieldExtInfo"}, {"LabelField", "LabelFieldExtInfo"},
            {"CheckBoxField", "CheckBoxFieldExtInfo"}, {"CalendarField", "CalendarFieldExtInfo"},
            {"ChartField", "ChartFieldExtInfo"}, {"DendrogramField", "DendrogramFieldExtInfo"},
            {"FormattedDocumentField", "FormattedDocFieldExtInfo"}, {"GanttChartField", "GanttChartFieldExtInfo"},
            {"GeographicalSchemaField", "GeographicalMapFieldExtInfo"}, {"GraphicalSchemaField", "FlowchartFieldExtInfo"},
            {"HTMLDocumentField", "HtmlFieldExtInfo"}, {"PictureField", "ImageFieldExtInfo"},
            {"ProgressBarField", "ProgressBarFieldExtInfo"}, {"RadioButtonField", "RadioButtonsFieldExtInfo"},
            {"SpreadsheetDocumentField", "SpreadSheetDocFieldExtInfo"}, {"TextDocumentField", "TextDocFieldExtInfo"},
            {"TrackBarField", "TrackBarFieldExtInfo"}, {"PlannerField", "PlannerFieldExtInfo"},
            {"PeriodField", "PeriodFieldExtInfo"}, {"PDFDocumentField", "PDFDocumentFieldExtInfo"},
            {"Label", "LabelDecorationExtInfo"}, {"Picture", "PictureDecorationExtInfo"}
        };
        for (Version version : new Version[] {Version.V8_3_12, Version.V8_3_16, Version.V8_3_27, Version.V8_5_1})
        {
            for (String[] field : fields)
            {
                cases.add(new Object[] {version, field[0], field[1]});
            }
        }
        return cases;
    }

    private final Version version;
    private final String kind;
    private final String classifier;

    public FormItemExtensionDefaultsTest(Version version, String kind, String classifier)
    {
        this.version = version;
        this.kind = kind;
        this.classifier = classifier;
    }

    @Test
    public void missingHolderGetsNativeDefaults() throws Exception
    {
        EObject form = model("Form");
        EObject item = model(isDecoration() ? "Decoration" : "FormField");
        setEnum(item, "type", kind);
        set(item, "name", "Item");
        EObject actual = FormElementWriter.ensureExtInfo(form, item, version);
        EObject expected = nativeFactory().create((EClass)item.eClass().getEPackage().getEClassifier(classifier),
            item, version);
        assertAttributes(expected, actual);
        if ("PDFDocumentField".equals(kind) && version.isGreaterThan(Version.V8_3_20))
        {
            EObject addition = (EObject)get(actual, "viewStatusAddition");
            assertNotNull(addition);
            assertEquals("ViewStatusAddition", get(addition, "type").toString());
            assertEquals(true, get(addition, "enabled"));
            assertSame(item, get(addition, "source"));
            assertNotNull(get(addition, "contextMenu"));
            assertNotNull(get(addition, "extendedTooltip"));
            assertNotNull(get(addition, "extInfo"));
        }
    }

    @Test
    public void retypingStartsWithDefaultsAndPreservesCompatibleValues() throws Exception
    {
        EObject form = model("Form");
        FormElementWriter.Kind itemKind = isDecoration()
            ? FormElementWriter.Kind.DECORATION : FormElementWriter.Kind.FIELD;
        if (!isDecoration())
        {
            create(form, FormElementWriter.Kind.ATTRIBUTE, "Value", null, version);
        }
        create(form, itemKind, "Item", isDecoration() ? null : "Value", version);
        EObject item = FormElementWriter.findFormItem(form, "Item");
        EObject before = EcoreUtil.copy(FormElementWriter.extInfoInstance(item));
        if (before.eClass().getEStructuralFeature("width") != null)
        {
            set(before, "width", 73);
            set(FormElementWriter.extInfoInstance(item), "width", 73);
        }
        setEnum(item, "type", kind);
        FormElementWriter.syncItemExtInfo(form, item, new ArrayList<>(), version);
        EObject actual = FormElementWriter.extInfoInstance(item);
        EObject expected = nativeFactory().create((EClass)item.eClass().getEPackage().getEClassifier(classifier),
            item, version);
        // Native ExtInfoManagementService copies matching, set properties after new-type initialization.
        for (EAttribute property : before.eClass().getEAllAttributes())
        {
            EStructuralFeature target = expected.eClass().getEStructuralFeature(property.getName());
            if (before.eIsSet(property) && target != null && target.getEType() == property.getEType()
                && target.isChangeable() && !target.isDerived() && !target.isTransient())
            {
                expected.eSet(target, before.eGet(property));
            }
        }
        assertAttributes(expected, actual);
        // Repeat writes must preserve holder identity and explicitly selected values, including false.
        EStructuralFeature enabled = actual.eClass().getEStructuralFeature("autoMaxWidth");
        if (enabled != null)
        {
            actual.eSet(enabled, false);
        }
        FormElementWriter.syncItemExtInfo(form, item, new ArrayList<>(), version);
        assertSame(actual, FormElementWriter.ensureExtInfo(form, item, version));
        if (enabled != null)
        {
            assertEquals(false, actual.eGet(enabled));
        }
    }

    private boolean isDecoration()
    {
        return "Label".equals(kind) || "Picture".equals(kind);
    }

    static EObject model(String classifier) throws Exception
    {
        EPackage pkg = (EPackage)Class.forName("com._1c.g5.v8.dt.form.model.FormPackage")
            .getField("eINSTANCE").get(null);
        return pkg.getEFactoryInstance().create((EClass)pkg.getEClassifier(classifier));
    }

    static void create(EObject form, FormElementWriter.Kind kind, String name, String bind, Version version)
    {
        assertNull(FormElementWriter.createMember(form, kind, name, null, bind, "en", null,
            false, null, null, null, version));
    }

    static Object get(EObject object, String property)
    {
        return object.eGet(object.eClass().getEStructuralFeature(property));
    }

    static void set(EObject object, String property, Object value)
    {
        object.eSet(object.eClass().getEStructuralFeature(property), value);
    }

    static void setEnum(EObject object, String property, String value)
    {
        EStructuralFeature feature = object.eClass().getEStructuralFeature(property);
        EEnum type = (EEnum)feature.getEType();
        EEnumLiteral literal = type.getEEnumLiteralByLiteral(value);
        object.eSet(feature, literal.getInstance());
    }

    static void assertAttributes(EObject expected, EObject actual)
    {
        assertNotNull(actual);
        assertEquals(expected.eClass(), actual.eClass());
        for (EAttribute property : expected.eClass().getEAllAttributes())
        {
            if (!property.isDerived() && !property.isTransient())
            {
                assertEquals(actual.eClass().getName() + "." + property.getName(),
                    expected.eGet(property), actual.eGet(property));
            }
        }
    }

    static IModelObjectFactory nativeFactory() throws Exception
    {
        String className = "com._1c.g5.v8.dt.form.FormObjectFactory";
        Bundle bundle = Platform.getBundle("com._1c.g5.v8.dt.form");
        Class<?> type = bundle == null ? Class.forName(className) : bundle.loadClass(className);
        IModelObjectFactory factory = (IModelObjectFactory)type.getConstructor().newInstance();
        // Only naming/project/type-management need an EDT workspace. Defaults come from the real factory.
        for (Field field : type.getDeclaredFields())
        {
            if (!field.getType().isInterface())
            {
                continue;
            }
            field.setAccessible(true);
            field.set(factory, Proxy.newProxyInstance(field.getType().getClassLoader(),
                new Class<?>[] {field.getType()}, (proxy, method, args) -> {
                    if (method.getReturnType() == String.class)
                    {
                        return "DefaultName";
                    }
                    if (method.getReturnType() == boolean.class)
                    {
                        return false;
                    }
                    return null;
                }));
        }
        return factory;
    }
}
