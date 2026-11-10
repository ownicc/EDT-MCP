// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.xtext.nodemodel.ICompositeNode;
import org.eclipse.xtext.nodemodel.impl.NodeModelBuilder;
import org.junit.Test;

import com._1c.g5.v8.dt.bsl.model.*;
import com._1c.g5.v8.dt.mcore.util.Environments;

/** Saved semantic ASTs and native Xtext nodes, without an editor or type-computer writes. */
public class BslCallDetailsTest
{
    @Test public void exactArgumentsUseUtf16ColumnsAndResolvedFormalNames()
    {
        String source = "// \uD83D\uDE00\r\n\tCall(\"\uD83D\uDE00\",\r\n  Nested(1, 2))";
        Invocation inv = call("Call");
        Expression first = BslFactory.eINSTANCE.createStringLiteral();
        Expression second = BslFactory.eINSTANCE.createInvocation();
        inv.getParams().add(first);
        inv.getParams().add(second);
        Method target = method("Call", "Text", "Value");
        FeatureEntry entry = BslFactory.eINSTANCE.createFeatureEntry();
        entry.setFeature(target);
        ((StaticFeatureAccess)inv.getMethodAccess()).getFeatureEntries().add(entry);
        NodeModelBuilder builder = new NodeModelBuilder();
        ICompositeNode root = builder.newRootNode(source);
        ICompositeNode callNode = builder.newCompositeNode(null, 0, root);
        builder.associateWithSemanticElement(callNode, inv);
        builder.newLeafNode(8, 5, null, false, null, callNode);
        node(builder, callNode, first, 13, 4);
        builder.newLeafNode(17, 5, null, false, null, callNode);
        node(builder, callNode, second, 22, 12);
        builder.newLeafNode(34, 1, null, false, null, callNode);

        Map<String, Object> detail = BslCallDetails.capture(inv, "CommonModules/Calc/Module.bsl", source);
        List<Map<String, Object>> args = arguments(detail);
        assertEquals("\"\uD83D\uDE00\"", args.get(0).get("text"));
        assertEquals("Text", args.get(0).get("formalName"));
        assertEquals("Nested(1, 2)", args.get(1).get("text"));
        assertEquals("Value", args.get(1).get("formalName"));
        assertEquals(Map.of("startLine", 2, "startColumn", 7, "endLine", 2, "endColumn", 11),
            args.get(0).get("range"));
        assertEquals(Map.of("startLine", 3, "startColumn", 3, "endLine", 3, "endColumn", 15),
            args.get(1).get("range"));
    }

    @Test public void unresolvedAndAmbiguousFeaturesDoNotInventFormalNamesOrServerKind()
    {
        Invocation inv = call("AtServerNamedButUnresolved");
        inv.getParams().add(BslFactory.eINSTANCE.createEmptyExpression());
        Map<String, Object> detail = BslCallDetails.capture(inv, "Module.bsl", "");
        assertEquals("unknown", detail.get("resolution"));
        assertEquals("unknown", detail.get("serverCall"));
        assertNull(arguments(detail).get(0).get("formalName"));
        assertEquals(Boolean.TRUE, arguments(detail).get(0).get("omitted"));
        for (String name : List.of("First", "Second"))
        {
            FeatureEntry entry = BslFactory.eINSTANCE.createFeatureEntry();
            entry.setFeature(method(name, "Param"));
            ((StaticFeatureAccess)inv.getMethodAccess()).getFeatureEntries().add(entry);
        }
        assertEquals("unknown", BslCallDetails.capture(inv, "Module.bsl", "").get("resolution"));
    }

    @Test public void nativeServerKindsRequireSemanticEnvironments()
    {
        com._1c.g5.v8.dt.bsl.model.Module module = BslFactory.eINSTANCE.createModule();
        module.setModuleType(ModuleType.FORM_MODULE);
        Method caller = method("Client");
        caller.setEnvironments(Environments.ALL_CLIENTS);
        Method callee = method("Server");
        callee.setEnvironments(Environments.SERVER);
        module.getMethods().add(caller);
        module.getMethods().add(callee);
        Invocation inv = call("Server");
        SimpleStatement statement = BslFactory.eINSTANCE.createSimpleStatement();
        statement.setLeft(inv);
        caller.getStatements().add(statement);
        FeatureEntry entry = BslFactory.eINSTANCE.createFeatureEntry();
        entry.setFeature(callee);
        ((StaticFeatureAccess)inv.getMethodAccess()).getFeatureEntries().add(entry);
        assertEquals("context", BslCallDetails.capture(inv, "Form/Module.bsl", "").get("serverCall"));
        Pragma pragma = BslFactory.eINSTANCE.createPragma();
        pragma.setSymbol("AtServerNoContext");
        callee.getPragmas().add(pragma);
        assertEquals("noContext", BslCallDetails.capture(inv, "Form/Module.bsl", "").get("serverCall"));
        caller.setEnvironments(Environments.SERVER);
        assertEquals("none", BslCallDetails.capture(inv, "Form/Module.bsl", "").get("serverCall"));
        caller.setEnvironments(Environments.EMPTY);
        assertEquals("unknown", BslCallDetails.capture(inv, "Form/Module.bsl", "").get("serverCall"));
    }

    @Test public void sourceBudgetTruncatesTextWithoutLosingExactRange()
    {
        String source = "Call(\"" + "x".repeat(20_000) + "\")";
        Invocation inv = call("Call");
        Expression arg = BslFactory.eINSTANCE.createStringLiteral();
        inv.getParams().add(arg);
        NodeModelBuilder builder = new NodeModelBuilder();
        ICompositeNode root = builder.newRootNode(source);
        node(builder, root, arg, 5, 20_002);
        Map<String, Object> argument = arguments(BslCallDetails.capture(inv, "Module.bsl", source)).get(0);
        assertEquals(Boolean.TRUE, argument.get("textTruncated"));
        assertTrue(((String)argument.get("text")).length() <= 4096);
        assertEquals(Map.of("startLine", 1, "startColumn", 6, "endLine", 1, "endColumn", 20_008),
            argument.get("range"));
    }

    @Test public void staleSourceIsRefusedBeforeReportingCoordinates()
    {
        com._1c.g5.v8.dt.bsl.model.Module module = BslFactory.eINSTANCE.createModule();
        NodeModelBuilder builder = new NodeModelBuilder();
        ICompositeNode root = builder.newRootNode("Call(1)");
        builder.associateWithSemanticElement(root, module);
        builder.newLeafNode(0, 7, null, false, null, root);
        assertNull(BslCallDetails.snapshotProblem(module, "Call(1)"));
        assertNotNull(BslCallDetails.snapshotProblem(module, "Call(2)"));
    }

    private static Invocation call(String name)
    {
        Invocation result = BslFactory.eINSTANCE.createInvocation();
        StaticFeatureAccess access = BslFactory.eINSTANCE.createStaticFeatureAccess();
        access.setName(name);
        result.setMethodAccess(access);
        return result;
    }

    private static Method method(String name, String... parameters)
    {
        Method result = BslFactory.eINSTANCE.createProcedure();
        result.setName(name);
        new ResourceImpl(URI.createURI("platform:/resource/Test/src/CommonModules/" + name + "/Module.bsl"))
            .getContents().add(result);
        for (String nameOfParam : parameters)
        {
            FormalParam param = BslFactory.eINSTANCE.createFormalParam();
            param.setName(nameOfParam);
            result.getFormalParams().add(param);
        }
        return result;
    }

    private static void node(NodeModelBuilder builder, ICompositeNode parent,
        Expression expression, int offset, int length)
    {
        ICompositeNode node = builder.newCompositeNode(null, 0, parent);
        builder.associateWithSemanticElement(node, expression);
        builder.newLeafNode(offset, length, null, false, null, node);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> arguments(Map<String, Object> detail)
    {
        return (List<Map<String, Object>>)detail.get("arguments");
    }
}
