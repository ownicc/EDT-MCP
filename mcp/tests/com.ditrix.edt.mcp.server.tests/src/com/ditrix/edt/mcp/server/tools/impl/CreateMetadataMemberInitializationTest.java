/*
 * Copyright (c) 2026 DitriX.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.ditrix.edt.mcp.server.tools.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.UUID;

import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.junit.Test;

import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.IV8Project;
import com._1c.g5.v8.dt.md.MdUtil;
import com._1c.g5.v8.dt.mcore.ButtonRepresentation;
import com._1c.g5.v8.dt.metadata.mdclass.BasicCommand;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassPackage;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.Operation;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;
import com._1c.g5.v8.dt.platform.version.Version;

/** Regression coverage for native designer defaults on members without a mandatory value type. */
public class CreateMetadataMemberInitializationTest
{
    @Test
    public void testEverySupportedTabularSectionHasProducedTypesAndTypeIdentifiers() throws Exception
    {
        for (String ownerKind : List.of("Catalog", "Document", "DataProcessor", "Report", "ExchangePlan", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "ChartOfAccounts", "ChartOfCharacteristicTypes", "ChartOfCalculationTypes", "BusinessProcess", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Task")) //$NON-NLS-1$
        {
            MdObject owner = (MdObject)EcoreUtil.create((EClass)MdClassPackage.eINSTANCE.getEClassifier(ownerKind));
            MdObject created = createWithNativeInitializer(owner, "tabularSections", ownerKind + "TabularSectionInitializer"); //$NON-NLS-1$ //$NON-NLS-2$
            EObject producedTypes = (EObject)value(created, "producedTypes"); //$NON-NLS-1$
            assertNotNull(ownerKind + " tabular section needs produced types", producedTypes); //$NON-NLS-1$
            assertEquals("TabularSectionTypes", producedTypes.eClass().getName()); //$NON-NLS-1$
            for (String kind : List.of("objectType", "rowType")) //$NON-NLS-1$ //$NON-NLS-2$
            {
                EObject type = (EObject)value(producedTypes, kind);
                assertNotNull(ownerKind + "." + kind, type); //$NON-NLS-1$
                assertTrue(value(type, "typeId") instanceof UUID); //$NON-NLS-1$
                assertTrue(value(type, "valueTypeId") instanceof UUID); //$NON-NLS-1$
            }
        }
    }

    @Test
    public void testCommandKeepsNativeAutomaticRepresentationWithoutDefaultingItsParameterType() throws Exception
    {
        BasicCommand command = (BasicCommand)createWithNativeInitializer(MdClassFactory.eINSTANCE.createCatalog(),
            "commands", "CatalogInitializer"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(ButtonRepresentation.AUTO, command.getRepresentation());
        assertNull(command.getCommandParameterType());
        // ActionsPanelTools is assigned by the same native initializer only for owners in a BM engine.
    }

    @Test
    public void testHttpUrlTemplateGetsTheNativeWildcard() throws Exception
    {
        URLTemplate template = (URLTemplate)createWithNativeInitializer(MdClassFactory.eINSTANCE.createHTTPService(),
            "urlTemplates", "URLTemplateInitializer"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("/*", template.getTemplate()); //$NON-NLS-1$
    }

    @Test
    public void testWebServiceOperationGetsTheNativeXmlSchemaReturnType() throws Exception
    {
        Operation operation = (Operation)createWithNativeInitializer(MdClassFactory.eINSTANCE.createWebService(),
            "operations", "OperationInitializer"); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(operation.getXdtoReturningValueType());
        assertEquals("string", operation.getXdtoReturningValueType().getName()); //$NON-NLS-1$
        assertEquals("http://www.w3.org/2001/XMLSchema", operation.getXdtoReturningValueType().getNsUri()); //$NON-NLS-1$
    }

    /**
     * Only the OSGi initializer registry is substituted. The returned objects and their defaults are
     * made by the installed EDT initializers, not fixtures that repeat the expectations.
     */
    private static MdObject createWithNativeInitializer(MdObject owner, String featureName,
        String initializerName) throws Exception
    {
        EReference feature = (EReference)owner.eClass().getEStructuralFeature(featureName);
        EClass elementType = feature.getEReferenceType();
        IModelObjectFactory factory = mock(IModelObjectFactory.class);
        // Load through the owning bundle so this also works in the OSGi test runtime without an
        // Import-Package for EDT's implementation package.
        Class<?> initializerClass = MdUtil.class.getClassLoader()
            .loadClass("com._1c.g5.v8.dt.md.model." + initializerName); //$NON-NLS-1$
        Object initializer = initializerClass.getDeclaredConstructor().newInstance();
        UUID[] nativeUuid = new UUID[1];
        doAnswer(invocation ->
        {
            MdObject nativeChild;
            if ("commands".equals(featureName)) //$NON-NLS-1$
            {
                nativeChild = (MdObject)initializerClass
                    .getMethod("createChildObject", EClass.class, EObject.class, Version.class) //$NON-NLS-1$
                    .invoke(initializer, elementType, owner, Version.V8_3_26);
            }
            else
            {
                nativeChild = (MdObject)initializerClass.getMethod("create", IV8Project.class, Version.class) //$NON-NLS-1$
                    .invoke(initializer, null, Version.V8_3_26);
            }
            nativeUuid[0] = nativeChild.getUuid();
            nativeChild.setComment("Native comment"); //$NON-NLS-1$
            nativeChild.getSynonym().put("en", "Native synonym"); //$NON-NLS-1$ //$NON-NLS-2$
            return nativeChild;
        }).when(factory).create(elementType, owner, Version.V8_3_26);

        MdObject existing = (MdObject)EcoreUtil.create(elementType);
        @SuppressWarnings("unchecked")
        List<MdObject> members = (List<MdObject>)owner.eGet(feature);
        members.add(existing);
        CreateMetadataTool.Props props = new CreateMetadataTool.Props();
        props.synonym = "Explicit synonym"; //$NON-NLS-1$
        props.comment = "Explicit comment"; //$NON-NLS-1$
        MdObject created = CreateMetadataTool.createMemberChild(new CreateMetadataTool.MemberChildSpec(factory,
            elementType, owner, Version.V8_3_26, "NewMember", props, "en", feature, null)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("NewMember", created.getName()); //$NON-NLS-1$
        assertNotNull(created.getUuid());
        assertSame(owner, created.eContainer());
        assertEquals(List.of(existing, created), members);
        assertEquals("Explicit synonym", created.getSynonym().get("en")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Explicit comment", created.getComment()); //$NON-NLS-1$
        // Check native UUID preservation after the caller has observed the actual default content.
        // The individual tests fail on their missing defaults if this route bypasses initialization.
        if (nativeUuid[0] != null)
        {
            assertEquals(nativeUuid[0], created.getUuid());
        }
        return created;
    }

    private static Object value(EObject object, String featureName)
    {
        return object.eGet(object.eClass().getEStructuralFeature(featureName));
    }
}
