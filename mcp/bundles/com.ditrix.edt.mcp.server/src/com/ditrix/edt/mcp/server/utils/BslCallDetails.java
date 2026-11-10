// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ditrix.edt.mcp.server.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.EcoreUtil2;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;

import com._1c.g5.v8.dt.bsl.common.Symbols;
import com._1c.g5.v8.dt.bsl.model.BslContextDefMethod;
import com._1c.g5.v8.dt.bsl.model.DynamicFeatureAccess;
import com._1c.g5.v8.dt.bsl.model.EmptyExpression;
import com._1c.g5.v8.dt.bsl.model.Expression;
import com._1c.g5.v8.dt.bsl.model.FeatureEntry;
import com._1c.g5.v8.dt.bsl.model.Invocation;
import com._1c.g5.v8.dt.bsl.model.Method;
import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.model.ModuleType;
import com._1c.g5.v8.dt.bsl.model.StaticFeatureAccess;
import com._1c.g5.v8.dt.mcore.Environmental;

/** Read-only call-site snapshots. No type computation, editor access, or shared cache mutation. */
public final class BslCallDetails
{
    private static final int SOURCE_BUDGET = 4096;
    private static final int MAX_ARGUMENTS = 100;

    private BslCallDetails() { }

    /** Refuse stale/dirty semantic text instead of attaching its coordinates to saved text. */
    public static String snapshotProblem(Module module, String savedSource)
    {
        INode node = NodeModelUtils.getNode(module);
        if (node == null || savedSource == null
            || !savedSource.equals(node.getRootNode().getText()))
        {
            return "Saved source does not match the semantic snapshot (dirty editor or stale AST). "
                + "Save the module, wait for EDT analysis, then retry includeCallDetails=true.";
        }
        if (module.eResource() != null && !module.eResource().getErrors().isEmpty())
        {
            return "The saved semantic snapshot has parse errors. Fix them and retry includeCallDetails=true.";
        }
        return null;
    }

    /** Builds detached JSON values while the caller holds the native model read boundary. */
    public static Map<String, Object> capture(Invocation invocation, String modulePath, String source)
    {
        EObject feature = resolvedFeature(invocation);
        Method method = sourceMethod(feature);
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("modulePath", modulePath);
        site.put("range", range(NodeModelUtils.findActualNodeFor(invocation), source));
        site.put("caller", identity(EcoreUtil2.getContainerOfType(invocation, Method.class), modulePath));
        Map<String, Object> callee = identity(method != null ? method : feature, null);
        callee.put("invokedName", invokedName(invocation));
        site.put("callee", callee);
        site.put("resolution", feature == null ? "unknown" : "resolved");
        site.put("serverCall", serverKind(invocation, feature, method));
        site.put("actualArgumentCount", invocation.getParams().size());
        List<Map<String, Object>> arguments = new ArrayList<>();
        int budget = SOURCE_BUDGET;
        for (int i = 0; i < Math.min(invocation.getParams().size(), MAX_ARGUMENTS); i++)
        {
            Expression expression = invocation.getParams().get(i);
            INode node = NodeModelUtils.findActualNodeFor(expression);
            Map<String, Object> argument = new LinkedHashMap<>();
            argument.put("index", i + 1);
            argument.put("formalName", formalName(feature, method, i, invocation.getParams().size()));
            argument.put("omitted", expression instanceof EmptyExpression);
            argument.put("range", range(node, source));
            String text = null;
            boolean truncated = false;
            if (node != null && validRange(node, source))
            {
                int length = Math.min(node.getLength(), budget);
                // Do not split a supplementary character at the source budget boundary.
                if (length > 0 && length < node.getLength()
                    && Character.isHighSurrogate(source.charAt(node.getOffset() + length - 1))) length--;
                text = source.substring(node.getOffset(), node.getOffset() + length);
                truncated = length < node.getLength();
                budget -= length;
            }
            argument.put("text", text);
            argument.put("textTruncated", truncated);
            arguments.add(argument);
        }
        site.put("arguments", arguments);
        site.put("argumentsTruncated", invocation.getParams().size() > MAX_ARGUMENTS);
        return site;
    }

    /** Only a unique native resolved target proves identity; spelling is never a fallback. */
    public static EObject resolvedFeature(Invocation invocation)
    {
        List<FeatureEntry> entries = null;
        if (invocation.getMethodAccess() instanceof StaticFeatureAccess access) entries = access.getFeatureEntries();
        else if (invocation.getMethodAccess() instanceof DynamicFeatureAccess access) entries = access.getFeatureEntries();
        EObject result = null;
        if (entries == null) return null;
        for (FeatureEntry entry : entries)
        {
            EObject feature = entry.getFeature();
            if (feature == null || feature.eIsProxy()) return null;
            if (result != null && result != feature && !EcoreUtil.getURI(result).equals(EcoreUtil.getURI(feature)))
                return null;
            result = feature;
        }
        return result;
    }

    public static boolean targets(Invocation invocation, Method target)
    {
        EObject feature = resolvedFeature(invocation);
        Method source = sourceMethod(feature);
        return source != null && EcoreUtil.getURI(source).equals(EcoreUtil.getURI(target));
    }

    /** Uses only the native source link, without deriving a module or method from its name. */
    private static Method sourceMethod(EObject feature)
    {
        if (feature instanceof Method method) return method;
        if (feature instanceof BslContextDefMethod linked && linked.getSourceUri() != null
            && feature.eResource() != null && feature.eResource().getResourceSet() != null)
        {
            try
            {
                EObject source = feature.eResource().getResourceSet().getEObject(linked.getSourceUri(), true);
                if (source instanceof Method method && !source.eIsProxy()) return method;
            }
            catch (RuntimeException e)
            {
                if (e instanceof org.eclipse.core.runtime.OperationCanceledException
                    || e instanceof java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted()) throw e;
                // A native source link may no longer resolve; its formal names/context stay unknown.
            }
        }
        return null;
    }

    private static String formalName(EObject feature, Method method, int index, int count)
    {
        if (method != null)
            return index < method.getFormalParams().size() ? method.getFormalParams().get(index).getName() : null;
        if (feature instanceof com._1c.g5.v8.dt.mcore.Method nativeMethod)
        {
            var params = nativeMethod.actualParamSet(count);
            if (params != null && index < params.getParams().size()) return params.getParams().get(index).getName();
        }
        return null;
    }

    private static Map<String, Object> identity(EObject feature, String fallbackPath)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("methodName", feature instanceof com._1c.g5.v8.dt.mcore.NamedElement named ? named.getName() : null);
        URI uri = feature == null || feature.eResource() == null ? null : EcoreUtil.getURI(feature);
        value.put("uri", uri == null ? null : uri.toString());
        String path = uri == null ? null : uri.trimFragment().toPlatformString(true);
        int src = path == null ? -1 : path.indexOf("/src/");
        value.put("modulePath", src < 0 ? fallbackPath : path.substring(src + 5));
        return value;
    }

    private static String invokedName(Invocation invocation)
    {
        if (invocation.getMethodAccess() instanceof StaticFeatureAccess access) return access.getName();
        if (invocation.getMethodAccess() instanceof DynamicFeatureAccess access) return access.getName();
        return null;
    }

    private static String serverKind(Invocation invocation, EObject feature, Method method)
    {
        if (feature == null) return "unknown";
        try
        {
            Environmental caller = EcoreUtil2.getContainerOfType(invocation, Environmental.class);
            Environmental target = EcoreUtil2.getContainerOfType(feature, Environmental.class);
            if (!invocation.isIsServerCall() && (caller == null || caller.environments() == null
                || caller.environments().isEmpty() || (!(feature instanceof BslContextDefMethod)
                    && (target == null || target.environments() == null || target.environments().isEmpty()))))
                return "unknown";
            boolean serverCall = invocation.isIsServerCall()
                || com._1c.g5.v8.dt.bsl.util.BslUtil.isCallFromClient(feature, invocation);
            if (!serverCall) return "none";
            if (method == null) return "unknown";
            Module module = EcoreUtil2.getContainerOfType(method, Module.class);
            if (module == null || module.getModuleType() == null) return "unknown";
            boolean noContext = com._1c.g5.v8.dt.bsl.model.util.BslUtil.hasPragma(method,
                Symbols.AT_SERVER_NO_CONTEXT_INTNL, Symbols.AT_SERVER_NO_CONTEXT_RUS)
                || (module.getModuleType() != ModuleType.FORM_MODULE && module.getModuleType() != ModuleType.COMMAND_MODULE);
            return noContext ? "noContext" : "context";
        }
        catch (RuntimeException e)
        {
            if (e instanceof org.eclipse.core.runtime.OperationCanceledException
                || e instanceof java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted()) throw e;
            return "unknown";
        }
    }

    private static boolean validRange(INode node, String source)
    {
        return source != null && node.getOffset() >= 0 && node.getLength() >= 0
            && node.getOffset() <= source.length() - node.getLength();
    }

    /** One-based UTF-16 line/column, with an exclusive end, matching native node offsets. */
    private static Map<String, Integer> range(INode node, String source)
    {
        if (node == null || !validRange(node, source)) return null;
        var start = NodeModelUtils.getLineAndColumn(node, node.getOffset());
        var end = NodeModelUtils.getLineAndColumn(node, node.getOffset() + node.getLength());
        return Map.of("startLine", start.getLine(), "startColumn", start.getColumn(),
            "endLine", end.getLine(), "endColumn", end.getColumn());
    }
}
