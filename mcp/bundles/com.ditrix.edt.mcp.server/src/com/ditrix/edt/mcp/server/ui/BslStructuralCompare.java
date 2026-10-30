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
import org.eclipse.core.runtime.jobs.ISchedulingRule;
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
import org.eclipse.swt.widgets.Canvas;
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
    private final BslDiffOverview overview;
    private List<MethodAwareBslDiff.Move> movePairs = List.of();
    private static final ISchedulingRule METHOD_RULE = new ISchedulingRule() {
        @Override public boolean contains(ISchedulingRule rule) { return rule == this; }
        @Override public boolean isConflicting(ISchedulingRule rule) { return rule == this; }
    };
    private boolean dark;
    private String fallbackReason;
    private boolean byteChanges;

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
        theme.select(0);
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
        overview = new BslDiffOverview(left.text, right.text, right.overview, this::color, () -> dark);
        Result initial = StructuralBslDiff.parse(
            "{\"language\":\"BSL\",\"status\":\"unchanged\",\"chunks\":[]}", before, after); //$NON-NLS-1$
        render(initial);
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
        overview.redraw();
    }

    static Composite create(Composite parent, String before, String after, Path executable,
        Consumer<String> fallback) throws Exception
    {
        if (executable == null) MethodAwareBslDiff.validateInput(before, after);
        BslStructuralCompare comparison = new BslStructuralCompare(parent, before, after);
        Display display = parent.getDisplay();
        Job job = Job.create(Messages.BslDiff_Computing, monitor -> {
            try
            {
                if (executable == null)
                {
                    var result = MethodAwareBslDiff.compute(before, after, monitor);
                    dispatch(display, () -> {
                        if (!comparison.root.isDisposed())
                        {
                            comparison.left.markMoves(result.leftMoves());
                            comparison.right.markMoves(result.rightMoves());
                            comparison.movePairs = result.moves();
                            comparison.fallbackReason = result.fallback();
                            comparison.byteChanges = result.byteChanges();
                            comparison.show(result.diff());
                        }
                    });
                }
                else
                {
                    Result result = StructuralBslDiff.compare(executable, before, after, monitor);
                    dispatch(display, () -> {
                        if (!comparison.root.isDisposed()) comparison.show(result);
                    });
                }
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
                    if (!comparison.root.isDisposed()) fallback.accept(Messages.BslDiff_Unavailable + " " + e.getMessage());
                });
            }
            return Status.OK_STATUS;
        });
        comparison.root.addDisposeListener(event -> job.cancel());
        if (executable == null) job.setRule(METHOD_RULE);
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
        render(result);
        changedRows.clear();
        for (int i = 0; i < result.rows().size(); i++)
        {
            var row = result.rows().get(i);
            if (left.changes.containsKey(row.left()) || right.changes.containsKey(row.right())
                || left.moves.contains(row.left()) || right.moves.contains(row.right())) changedRows.add(i);
        }
        boolean changed = overview.hasChanges();
        previous.setEnabled(changed);
        next.setEnabled(changed);
        summary.setText(result.language().equals("BSL") //$NON-NLS-1$
            ? (changedRows.isEmpty() ? Messages.BslDiff_NoStructuralChanges : Messages.BslDiff_Structural)
            : Messages.BslDiff_TextFallback + " " + result.language()); //$NON-NLS-1$
        if (changedRows.isEmpty() && byteChanges) summary.setText(Messages.BslDiff_FormattingOnly);
        if (fallbackReason != null) summary.setText(summary.getText() + " — " + fallbackReason);
        summary.getParent().layout(true, true);
        if (changed) navigate(true);
    }

    private void render(Result result)
    {
        left.show(result, true);
        right.show(result, false);
        overview.show(result, movePairs);
    }

    private void navigate(boolean forward) { overview.navigate(forward); }

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
        final Canvas overview;
        final List<String> lines;
        final Map<Integer, List<Lexeme>> lexical = new HashMap<>();
        final Map<Integer, List<Span>> changes = new HashMap<>();
        final java.util.Set<Integer> moves = new java.util.HashSet<>();
        List<Integer> sourceLines = List.of();

        Side(Composite parent, String title, String source, Injector injector, boolean added) throws Exception
        {
            lines = StructuralBslDiff.lines(source);
            Composite panel = new Composite(parent, SWT.NONE);
            panel.setLayout(new GridLayout(1, false));
            new Label(panel, SWT.NONE).setText(title);
            Composite body = new Composite(panel, SWT.NONE);
            body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            GridLayout bodyLayout = new GridLayout(added ? 2 : 1, false);
            bodyLayout.marginWidth = 0;
            bodyLayout.marginHeight = 0;
            bodyLayout.horizontalSpacing = 0;
            body.setLayout(bodyLayout);
            text = new StyledText(body, SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
            text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
            overview = added ? new Canvas(body, SWT.DOUBLE_BUFFERED) : null;
            if (overview != null)
            {
                GridData rulerData = new GridData(SWT.RIGHT, SWT.FILL, false, true);
                rulerData.widthHint = 18;
                overview.setLayoutData(rulerData);
            }
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
                        StyleRange style = new StyleRange(event.lineOffset + start, end - start,
                            changed ? color(dark ? DARK_CHANGED_FOREGROUND
                                : added ? new RGB(0, 78, 34) : new RGB(124, 32, 40))
                                : dark ? color(darkForeground(category)) : attribute == null ? null : attribute.getForeground(),
                            changed ? color(dark ? (added ? DARK_ADDED : DARK_REMOVED)
                                : added ? new RGB(169, 221, 184) : new RGB(244, 185, 181))
                                : null,
                            changed ? SWT.BOLD : attribute == null ? SWT.NORMAL
                                : attribute.getStyle() & (SWT.BOLD | SWT.ITALIC));
                        if (changed)
                        {
                            style.underline = true;
                            style.underlineStyle = SWT.UNDERLINE_SINGLE;
                            style.underlineColor = color(dark ? (added ? new RGB(112, 230, 137) : new RGB(255, 132, 132))
                                : added ? new RGB(120, 184, 141) : new RGB(218, 141, 136));
                        }
                        styles.add(style);
                    }
                    start = end;
                }
                event.styles = styles.toArray(StyleRange[]::new);
            });
            text.addLineBackgroundListener(event -> {
                int row = text.getLineAtOffset(event.lineOffset);
                if (row >= sourceLines.size()) return;
                Integer original = sourceLines.get(row);
                if (original == null) event.lineBackground = color(dark ? new RGB(40, 42, 46) : new RGB(236, 239, 243));
                else if (changes.containsKey(original))
                    event.lineBackground = color(dark ? (added ? new RGB(30, 57, 37) : new RGB(65, 34, 36))
                        : added ? new RGB(216, 242, 221) : new RGB(255, 224, 223));
                else if (moves.contains(original)) event.lineBackground = color(dark ? new RGB(30, 47, 70) : new RGB(224, 237, 255));
            });
            text.addPaintListener(event -> {
                int top = text.getTopIndex();
                for (int row = top; row < sourceLines.size(); row++)
                {
                    int y = text.getLinePixel(row), height = text.getLineHeight(), gutter = text.getLeftMargin();
                    if (y > text.getClientArea().height) break;
                    Integer original = sourceLines.get(row);
                    if (original == null)
                    {
                        var clip = event.gc.getClipping();
                        event.gc.setClipping(clip.intersection(new org.eclipse.swt.graphics.Rectangle(0, y, text.getClientArea().width, height)));
                        event.gc.setForeground(color(dark ? new RGB(55, 58, 63) : new RGB(220, 225, 232)));
                        for (int x = -height; x < text.getClientArea().width; x += 10)
                            event.gc.drawLine(x, y + height, x + height, y);
                        event.gc.setClipping(clip);
                        continue;
                    }
                    boolean changed = changes.containsKey(original), moved = moves.contains(original);
                    event.gc.setBackground(text.getBackground());
                    event.gc.fillRectangle(0, y, gutter, height);
                    event.gc.setFont(changed || moved ? JFaceResources.getFontRegistry().getBold(JFaceResources.TEXT_FONT) : text.getFont());
                    event.gc.setForeground(changed
                        ? color(dark ? (added ? new RGB(112, 230, 137) : new RGB(255, 132, 132))
                            : added ? new RGB(0, 80, 20) : new RGB(138, 20, 20))
                        : moved ? color(dark ? new RGB(138, 188, 255) : new RGB(18, 75, 142))
                        : dark ? color(new RGB(150, 150, 150)) : color(new RGB(85, 85, 85)));
                    event.gc.drawString(changed ? (added ? "+" : "−") : moved ? "↕" : "", gutter - 61, y, true);
                    String number = Integer.toString(original + 1);
                    event.gc.drawString(number, gutter - 7 - event.gc.stringExtent(number).x, y, true);
                    if (changed || moved) event.gc.fillRectangle(gutter - 4, y, 3, height);
                    if (!changed) continue;
                    event.gc.setForeground(color(dark ? DARK_CHANGED_FOREGROUND
                        : added ? new RGB(0, 78, 34) : new RGB(124, 32, 40)));
                    int lineOffset = text.getOffsetAtLine(row);
                    var selection = text.getSelection();
                    // Limit geometry queries and whitespace drawing to this viewport, including after horizontal scrolling.
                    int first = text.getOffsetAtPoint(new org.eclipse.swt.graphics.Point(gutter, y + height / 2));
                    if (first < 0) continue;
                    int last = text.getOffsetAtPoint(new org.eclipse.swt.graphics.Point(
                        Math.max(gutter, text.getClientArea().width - 5), y + height / 2));
                    int from = Math.max(0, first - lineOffset - 1);
                    int until = last < 0 ? lines.get(original).length() : last - lineOffset + 1;
                    for (Span span : changes.get(original))
                    {
                        if (span.start() >= until) break;
                        for (int column = Math.max(span.start(), from);
                            column < Math.min(span.start() + span.length(), until); column++)
                        {
                            char c = lines.get(original).charAt(column);
                            int offset = lineOffset + column;
                            if ((c != ' ' && c != '\t') || (offset >= selection.x && offset < selection.y)) continue;
                            var point = text.getLocationAtOffset(offset);
                            if (point.x < gutter) continue;
                            event.gc.drawString(c == '\t' ? "→" : "·", point.x, point.y, true);
                        }
                    }
                }
            });
        }

        void markMoves(List<Span> spans)
        {
            moves.clear();
            for (Span span : spans) moves.add(span.line());
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
