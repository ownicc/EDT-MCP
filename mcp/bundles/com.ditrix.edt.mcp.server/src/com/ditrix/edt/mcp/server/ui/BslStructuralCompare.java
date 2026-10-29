/** MCP Server for EDT. Licensed under AGPL-3.0-or-later. */
package com.ditrix.edt.mcp.server.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.xtext.ui.editor.model.DocumentTokenSourceAccess;
import org.eclipse.xtext.ui.editor.syntaxcoloring.AbstractAntlrTokenToAttributeIdMapper;
import org.eclipse.xtext.ui.editor.syntaxcoloring.ITextAttributeProvider;

import com.ditrix.edt.mcp.server.Activator;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Result;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Span;
import com.google.inject.Injector;

/** Detached native text controls. No live file, document or write action is attached. */
final class BslStructuralCompare
{
    static final RGB DARK_BACKGROUND = new RGB(30, 30, 30);
    static final RGB DARK_ADDED = new RGB(38, 112, 58);
    static final RGB DARK_REMOVED = new RGB(156, 48, 48);
    static final RGB DARK_CHANGED_FOREGROUND = new RGB(255, 255, 255);
    private record Lexeme(int start, int end, String category, TextAttribute attribute) { }
    private final Composite root;
    private final Label summary;
    private final Button previous;
    private final Button next;
    private final Side left;
    private final Side right;
    private final TreeSet<Integer> changedRows = new TreeSet<>();
    private final Map<RGB, Color> colors = new HashMap<>();
    private boolean dark = true;
    private int selectedRow = -1;

    private BslStructuralCompare(Composite parent, String before, String after) throws Exception
    {
        root = new Composite(parent, SWT.NONE);
        root.addDisposeListener(event -> colors.values().forEach(Color::dispose));
        root.setLayout(new GridLayout(1, false));
        Composite toolbar = new Composite(root, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        toolbar.setLayout(new GridLayout(4, false));
        summary = new Label(toolbar, SWT.WRAP);
        summary.setText(Messages.BslDiff_Computing);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Combo theme = new Combo(toolbar, SWT.DROP_DOWN | SWT.READ_ONLY);
        theme.setItems(Messages.BslDiff_Light, Messages.BslDiff_Dark);
        theme.select(1);
        theme.addListener(SWT.Selection, event -> {
            dark = theme.getSelectionIndex() == 1;
            applyTheme();
        });
        previous = new Button(toolbar, SWT.PUSH);
        previous.setText(Messages.BslDiff_Previous);
        next = new Button(toolbar, SWT.PUSH);
        next.setText(Messages.BslDiff_Next);
        previous.setEnabled(false);
        next.setEnabled(false);
        previous.addListener(SWT.Selection, event -> navigate(false));
        next.addListener(SWT.Selection, event -> navigate(true));
        SashForm columns = new SashForm(root, SWT.HORIZONTAL);
        columns.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Injector injector = bslInjector();
        left = new Side(columns, Messages.BslReview_Current, before, injector, false);
        right = new Side(columns, Messages.BslReview_Proposed, after, injector, true);
        columns.setWeights(1, 1);
        synchronize(left.text, right.text);
        synchronize(right.text, left.text);
        Result initial = StructuralBslDiff.parse(
            "{\"language\":\"BSL\",\"status\":\"unchanged\",\"chunks\":[]}", before, after); //$NON-NLS-1$
        left.show(initial, true);
        right.show(initial, false);
        applyTheme();
    }

    static RGB darkForeground(String category)
    {
        return switch (category == null ? "" : category) //$NON-NLS-1$
        {
            case "BSL_Keywords" -> new RGB(215, 174, 251); //$NON-NLS-1$
            case "Strings" -> new RGB(230, 176, 139); //$NON-NLS-1$
            case "Numbers" -> new RGB(195, 232, 141); //$NON-NLS-1$
            case "Comment" -> new RGB(156, 179, 139); //$NON-NLS-1$
            case "BSL_Pragmas", "Preprocessor" -> new RGB(229, 210, 140); //$NON-NLS-1$ //$NON-NLS-2$
            case "Builtin function" -> new RGB(137, 221, 216); //$NON-NLS-1$
            default -> new RGB(212, 212, 212);
        };
    }

    private Color color(RGB rgb)
    {
        return colors.computeIfAbsent(rgb, value -> new Color(root.getDisplay(), value));
    }

    private void applyTheme()
    {
        Display display = root.getDisplay();
        for (Side side : List.of(left, right))
        {
            side.text.setBackground(dark ? color(DARK_BACKGROUND) : display.getSystemColor(SWT.COLOR_LIST_BACKGROUND));
            side.text.setForeground(dark ? color(darkForeground(null)) : display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            side.text.redrawRange(0, side.text.getCharCount(), true);
            side.text.redraw();
        }
    }

    static Composite create(Composite parent, String before, String after, Path executable,
        Consumer<String> fallback) throws Exception
    {
        BslStructuralCompare comparison = new BslStructuralCompare(parent, before, after);
        Display display = parent.getDisplay();
        Job job = Job.create(Messages.BslDiff_Computing, monitor -> {
            try
            {
                Result result = StructuralBslDiff.compare(executable, before, after, monitor);
                dispatch(display, () -> {
                    if (!comparison.root.isDisposed()) comparison.show(result);
                });
            }
            catch (org.eclipse.core.runtime.OperationCanceledException e) { return Status.CANCEL_STATUS; }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return Status.CANCEL_STATUS;
            }
            catch (Exception e)
            {
                Activator.logInfo("Structural BSL comparison unavailable: " + e.getMessage()); //$NON-NLS-1$
                dispatch(display, () -> {
                    if (!comparison.root.isDisposed()) fallback.accept(Messages.BslDiff_Unavailable);
                });
            }
            return Status.OK_STATUS;
        });
        comparison.root.addDisposeListener(event -> job.cancel());
        job.setSystem(true);
        job.schedule();
        return comparison.root;
    }

    private static void dispatch(Display display, Runnable update)
    {
        if (display.isDisposed()) return;
        try { display.asyncExec(update); }
        catch (org.eclipse.swt.SWTException e)
        {
            if (e.code != SWT.ERROR_DEVICE_DISPOSED) throw e;
        }
    }

    private void show(Result result)
    {
        left.show(result, true);
        right.show(result, false);
        changedRows.clear();
        for (int i = 0; i < result.rows().size(); i++)
        {
            var row = result.rows().get(i);
            if (left.changes.containsKey(row.left()) || right.changes.containsKey(row.right())) changedRows.add(i);
        }
        previous.setEnabled(!changedRows.isEmpty());
        next.setEnabled(!changedRows.isEmpty());
        summary.setText(result.language().equals("BSL") //$NON-NLS-1$
            ? (changedRows.isEmpty() ? Messages.BslDiff_NoStructuralChanges : Messages.BslDiff_Structural)
            : Messages.BslDiff_TextFallback + " " + result.language()); //$NON-NLS-1$
        summary.getParent().layout(true, true);
        if (!changedRows.isEmpty()) navigate(true);
    }

    private void navigate(boolean forward)
    {
        Integer row = forward ? changedRows.higher(selectedRow) : changedRows.lower(selectedRow);
        if (row == null) row = forward ? changedRows.first() : changedRows.last();
        selectedRow = row;
        int top = Math.max(0, row - 3);
        for (Side side : List.of(left, right))
        {
            side.text.setTopIndex(top);
            side.text.setSelection(side.text.getOffsetAtLine(row));
            side.text.redraw();
        }
    }

    private static void synchronize(StyledText source, StyledText target)
    {
        Runnable sync = () -> {
            if (!source.isDisposed() && !target.isDisposed()) target.setTopPixel(source.getTopPixel());
        };
        source.getVerticalBar().addListener(SWT.Selection, event -> sync.run());
        source.addListener(SWT.MouseWheel, event -> source.getDisplay().asyncExec(sync));
        source.addListener(SWT.KeyUp, event -> sync.run());
    }

    private static Injector bslInjector() throws Exception
    {
        var bundle = Platform.getBundle("com._1c.g5.v8.dt.bsl.ui"); //$NON-NLS-1$
        if (bundle == null) throw new IllegalStateException("BSL editor is not installed"); //$NON-NLS-1$
        bundle.start(org.osgi.framework.Bundle.START_TRANSIENT);
        Class<?> activator = bundle.loadClass("com._1c.g5.v8.dt.bsl.ui.internal.BslActivator"); //$NON-NLS-1$
        Object instance = activator.getMethod("getInstance").invoke(null); //$NON-NLS-1$
        return (Injector)activator.getMethod("getInjector", String.class) //$NON-NLS-1$
            .invoke(instance, "com._1c.g5.v8.dt.bsl.Bsl"); //$NON-NLS-1$
    }

    private final class Side
    {
        final StyledText text;
        final List<String> lines;
        final Map<Integer, List<Lexeme>> lexical = new HashMap<>();
        final Map<Integer, List<Span>> changes = new HashMap<>();
        List<Integer> sourceLines = List.of();

        Side(Composite parent, String title, String source, Injector injector, boolean added) throws Exception
        {
            lines = StructuralBslDiff.lines(source);
            Composite panel = new Composite(parent, SWT.NONE);
            panel.setLayout(new GridLayout(1, false));
            new Label(panel, SWT.NONE).setText(title);
            text = new StyledText(panel, SWT.READ_ONLY | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
            text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            text.setFont(JFaceResources.getTextFont());
            text.setTabs(4);
            text.setMargins(64, 4, 4, 4);
            text.getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
                @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) { event.result = title; }
            });
            Document document = new Document(source);
            var tokens = injector.getInstance(DocumentTokenSourceAccess.class).getTokens(document, false);
            var mapper = injector.getInstance(AbstractAntlrTokenToAttributeIdMapper.class);
            var attributes = injector.getInstance(ITextAttributeProvider.class);
            for (var token : tokens)
            {
                int end = Math.min(source.length(), token.getOffset() + token.getLength());
                if (end <= token.getOffset()) continue;
                String category = mapper.getId(token.getLexerTokenType());
                TextAttribute attribute = attributes.getAttribute(category);
                for (int line = document.getLineOfOffset(token.getOffset());
                    line <= document.getLineOfOffset(end - 1) && line < lines.size(); line++)
                {
                    var region = document.getLineInformation(line);
                    int a = Math.max(0, token.getOffset() - region.getOffset());
                    int b = Math.min(region.getLength(), end - region.getOffset());
                    if (a < b) lexical.computeIfAbsent(line, key -> new ArrayList<>()).add(new Lexeme(a,b,category,attribute));
                }
            }
            text.addLineStyleListener(event -> {
                int row = text.getLineAtOffset(event.lineOffset);
                if (row >= sourceLines.size() || sourceLines.get(row) == null) return;
                int original = sourceLines.get(row);
                List<Lexeme> lexemes = lexical.getOrDefault(original, List.of());
                List<Span> spans = changes.getOrDefault(original, List.of());
                TreeSet<Integer> boundaries = new TreeSet<>(List.of(0, event.lineText.length()));
                for (Lexeme lexeme : lexemes) { boundaries.add(lexeme.start()); boundaries.add(lexeme.end()); }
                for (Span span : spans) { boundaries.add(span.start()); boundaries.add(span.start() + span.length()); }
                List<StyleRange> styles = new ArrayList<>();
                Integer start = null;
                for (int end : boundaries)
                {
                    if (start != null && end > start)
                    {
                        TextAttribute attribute = null;
                        String category = null;
                        for (Lexeme lexeme : lexemes)
                            if (lexeme.start() <= start && start < lexeme.end())
                            {
                                attribute = lexeme.attribute();
                                category = lexeme.category();
                                break;
                            }
                        boolean changed = false;
                        for (Span span : spans)
                            if (span.start() <= start && start < span.start() + span.length()) { changed = true; break; }
                        styles.add(new StyleRange(event.lineOffset + start, end - start,
                            dark ? color(changed ? DARK_CHANGED_FOREGROUND : darkForeground(category))
                                : attribute == null ? null : attribute.getForeground(),
                            changed ? color(dark ? (added ? DARK_ADDED : DARK_REMOVED)
                                : added ? new RGB(156, 221, 168) : new RGB(243, 179, 179))
                                : dark || attribute == null ? null : attribute.getBackground(),
                            attribute == null ? SWT.NORMAL : attribute.getStyle() & (SWT.BOLD | SWT.ITALIC)));
                    }
                    start = end;
                }
                event.styles = styles.toArray(StyleRange[]::new);
            });
            text.addPaintListener(event -> {
                int top = text.getTopIndex();
                for (int row = top; row < sourceLines.size(); row++)
                {
                    int y = text.getLinePixel(row);
                    if (y > text.getClientArea().height) break;
                    Integer original = sourceLines.get(row);
                    if (original != null)
                    {
                        event.gc.setForeground(changes.containsKey(original)
                            ? color(dark ? (added ? new RGB(112, 230, 137) : new RGB(255, 132, 132))
                                : added ? new RGB(0, 80, 20) : new RGB(180, 36, 36))
                            : dark ? color(new RGB(150, 150, 150))
                                : text.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
                        event.gc.drawString(Integer.toString(original + 1), 6, y, true);
                    }
                }
            });
        }

        void show(Result result, boolean lhs)
        {
            changes.clear();
            for (Span span : lhs ? result.left() : result.right())
                changes.computeIfAbsent(span.line(), key -> new ArrayList<>()).add(span);
            sourceLines = result.rows().stream().map(row -> lhs ? row.left() : row.right()).toList();
            text.setText(String.join("\n", sourceLines.stream().map(line -> line == null ? "" : lines.get(line)).toList())); //$NON-NLS-1$ //$NON-NLS-2$
            text.redraw();
        }
    }
}
