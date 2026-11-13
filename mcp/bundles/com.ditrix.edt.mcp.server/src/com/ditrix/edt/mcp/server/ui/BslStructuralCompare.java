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
import com.ditrix.edt.mcp.server.preferences.PreferenceConstants;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Result;
import com.ditrix.edt.mcp.server.ui.StructuralBslDiff.Span;
import com.google.inject.Injector;

/** Detached native text controls. No live file, document or write action is attached. */
final class BslStructuralCompare
{
    static final RGB DARK_BACKGROUND = new RGB(30, 30, 30);
    static final RGB DARK_ADDED = new RGB(29, 61, 39);
    static final RGB DARK_REMOVED = new RGB(68, 35, 38);
    private record Lexeme(int start, int end, String category, TextAttribute attribute) { }
    private final Composite root;
    private final Label summary;
    private final Button previous;
    private final Button next;
    private final Button compareMethod;
    private final Label selectedMethod;
    private final Composite methodPanel;
    private final Label methodKind;
    private final Label methodRoute;
    private final String before;
    private final String after;
    private final Side left;
    private final Side right;
    private final TreeSet<Integer> changedRows = new TreeSet<>();
    private final Map<RGB, Color> colors = new HashMap<>();
    private final BslDiffOverview overview;
    private final BslDiffSearchBar search;
    private final BslMethodNavigationPanel navigation;
    private List<StructuralBslDiff.Row> searchRows = List.of();
    private List<MethodAwareBslDiff.Move> movePairs = List.of();
    private List<MethodAwareBslDiff.MethodChange> methodChanges = List.of();
    private static final ISchedulingRule METHOD_RULE = new ISchedulingRule() {
        @Override public boolean contains(ISchedulingRule rule) { return rule == this; }
        @Override public boolean isConflicting(ISchedulingRule rule) { return rule == this; }
    };
    private boolean dark;
    private String fallbackReason;
    private String navigationFallback;
    private boolean byteChanges;

    private BslStructuralCompare(Composite parent, String before, String after) throws Exception
    {
        this(parent, before, after, 0, 0, true);
    }

    private BslStructuralCompare(Composite parent, String before, String after, int leftOffset,
        int rightOffset, boolean allowMethodComparison) throws Exception
    {
        this.before = before;
        this.after = after;
        root = new Composite(parent, SWT.NONE);
        root.addDisposeListener(event -> colors.values().forEach(Color::dispose));
        root.setLayout(new GridLayout(1, false));
        Composite toolbar = new Composite(root, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        toolbar.setLayout(new GridLayout(6, false));
        summary = new Label(toolbar, SWT.WRAP);
        summary.setText(Messages.BslDiff_Computing);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button methodsToggle = new Button(toolbar, SWT.TOGGLE);
        methodsToggle.setText(Messages.BslDiff_Methods);
        methodsToggle.setSelection(true);
        GridData toggleData = new GridData();
        toggleData.exclude = !allowMethodComparison;
        methodsToggle.setLayoutData(toggleData);
        methodsToggle.setVisible(allowMethodComparison);
        Combo theme = new Combo(toolbar, SWT.DROP_DOWN | SWT.READ_ONLY);
        theme.setItems(Messages.BslDiff_Light, Messages.BslDiff_Dark);
        theme.select(0);
        theme.addListener(SWT.Selection, event -> {
            dark = theme.getSelectionIndex() == 1;
            applyTheme();
        });
        Button find = new Button(toolbar, SWT.PUSH);
        find.setText(Messages.BslDiff_Find);
        find.setToolTipText("Ctrl+F"); //$NON-NLS-1$
        previous = new Button(toolbar, SWT.PUSH);
        previous.setText(Messages.BslDiff_Previous);
        next = new Button(toolbar, SWT.PUSH);
        next.setText(Messages.BslDiff_Next);
        previous.setEnabled(false);
        next.setEnabled(false);
        previous.addListener(SWT.Selection, event -> navigate(false));
        next.addListener(SWT.Selection, event -> navigate(true));
        methodPanel = new Composite(root, SWT.BORDER);
        GridData panelData = new GridData(SWT.FILL, SWT.TOP, true, false);
        panelData.exclude = !allowMethodComparison;
        methodPanel.setLayoutData(panelData);
        methodPanel.setVisible(allowMethodComparison);
        GridLayout panelLayout = new GridLayout(5, false);
        panelLayout.horizontalSpacing = 12;
        methodPanel.setLayout(panelLayout);
        new Label(methodPanel, SWT.NONE).setText(Messages.BslDiff_SelectedMethod);
        selectedMethod = new Label(methodPanel, SWT.NONE);
        selectedMethod.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT));
        GridData methodNameData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        methodNameData.widthHint = 220;
        methodNameData.minimumWidth = 0;
        selectedMethod.setLayoutData(methodNameData);
        methodKind = new Label(methodPanel, SWT.NONE);
        methodRoute = new Label(methodPanel, SWT.NONE);
        compareMethod = new Button(methodPanel, SWT.PUSH);
        compareMethod.setText(Messages.BslDiff_CompareMethod);
        compareMethod.setEnabled(false);
        SashForm content = new SashForm(root, SWT.HORIZONTAL);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite navigationHost = new Composite(content, SWT.NONE);
        navigationHost.setLayout(new org.eclipse.swt.layout.FillLayout());
        SashForm columns = new SashForm(content, SWT.HORIZONTAL);
        Injector injector = bslInjector();
        left = new Side(columns, Messages.BslReview_Current, before, injector, false, leftOffset);
        right = new Side(columns, Messages.BslReview_Proposed, after, injector, true, rightOffset);
        columns.setWeights(1, 1);
        overview = new BslDiffOverview(left.text, right.text, right.overview, this::color, () -> dark);
        navigation = new BslMethodNavigationPanel(navigationHost, overview::selectEntry, this::openMethod);
        content.setWeights(24, 76);
        if (!allowMethodComparison) content.setMaximizedControl(columns);
        methodsToggle.addListener(SWT.Selection, event -> content.setMaximizedControl(methodsToggle.getSelection() ? null : columns));
        search = new BslDiffSearchBar(root,
            (query, scope, matchCase) -> BslDiffSearch.find(before, after, searchRows, query, scope, matchCase),
            overview::revealMatch, List.of(left.text, right.text));
        search.row.moveAbove(content);
        find.addListener(SWT.Selection, event -> search.open());
        compareMethod.addListener(SWT.Selection, event -> {
            var method = overview.selectedMethod();
            openMethod(method);
        });
        overview.onMethodSelection(method -> {
            navigation.select(method, overview.selectedRow());
            compareMethod.setEnabled(allowMethodComparison && BslMethodNavigation.canCompare(method));
            var entry = navigation.selectedEntry();
            String name = method != null ? method.name() : entry != null ? entry.name() : Messages.BslDiff_NoMethodSelected;
            selectedMethod.setText(name);
            selectedMethod.setToolTipText(method == null ? Messages.BslDiff_SelectMethodHint : name);
            compareMethod.setToolTipText(method == null ? Messages.BslDiff_SelectMethodHint
                : BslMethodNavigation.canCompare(method) ? Messages.BslDiff_CompareMethod + ": " + name //$NON-NLS-1$
                : Messages.BslDiff_MethodAbsent);
            methodKind.setText(method == null ? "" : BslMethodNavigationPanel.kindLabel(method.kind())); //$NON-NLS-1$
            methodRoute.setText(method == null ? "" : org.eclipse.osgi.util.NLS.bind(Messages.BslDiff_MethodRoute, //$NON-NLS-1$
                methodRange(method.left(), leftOffset), methodRange(method.right(), rightOffset)));
            methodPanel.layout(true, true);
        });
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

    static boolean wholeLineChanged(String line, List<Span> spans)
    {
        int until = 0;
        for (Span span : spans)
        {
            if (span.length() == 0) continue;
            if (span.start() > until && !line.substring(until, span.start()).isBlank()) return false;
            until = Math.max(until, span.start() + span.length());
        }
        return until > 0 && line.substring(until).isBlank();
    }

    private Color color(RGB rgb)
    {
        return colors.computeIfAbsent(rgb, value -> new Color(root.getDisplay(), value));
    }

    private void applyTheme()
    {
        for (Side side : List.of(left, right))
        {
            side.text.setBackground(color(dark ? DARK_BACKGROUND : new RGB(255, 255, 255)));
            side.text.setForeground(color(dark ? darkForeground(null) : new RGB(0, 0, 0)));
            side.text.redrawRange(0, side.text.getCharCount(), true);
            side.text.redraw();
        }
        navigation.applyTheme(color(dark ? DARK_BACKGROUND : new RGB(255, 255, 255)),
            color(dark ? darkForeground(null) : new RGB(0, 0, 0)));
        overview.redraw();
    }

    static Composite create(Composite parent, String before, String after, Path executable,
        Consumer<String> fallback) throws Exception
    {
        return create(parent, before, after, executable, fallback, 0, 0, true);
    }

    static Composite create(Composite parent, String before, String after, Path executable,
        Consumer<String> fallback, int leftOffset, int rightOffset, boolean allowMethodComparison) throws Exception
    {
        long startedNanos = System.nanoTime();
        if (executable == null) MethodAwareBslDiff.validateInput(before, after);
        int seconds = Activator.getDefault().getPreferenceStore()
            .getInt(PreferenceConstants.PREF_BSL_DIFF_TIMEOUT_SECONDS);
        java.time.Duration timeout = java.time.Duration.ofSeconds(Math.max(
            PreferenceConstants.MIN_BSL_DIFF_TIMEOUT_SECONDS,
            Math.min(PreferenceConstants.MAX_BSL_DIFF_TIMEOUT_SECONDS, seconds)));
        BslStructuralCompare comparison = new BslStructuralCompare(parent, before, after, leftOffset, rightOffset, allowMethodComparison);
        if (executable != null) comparison.navigationFallback = "external-comparison:method-identities-unavailable"; //$NON-NLS-1$
        Display display = parent.getDisplay();
        Job job = Job.create(Messages.BslDiff_Computing, monitor -> {
            try
            {
                if (executable == null)
                {
                    var result = MethodAwareBslDiff.compute(before, after, monitor, timeout);
                    dispatch(display, () -> {
                        if (!comparison.root.isDisposed())
                        {
                            comparison.left.markMoves(result.leftMoves());
                            comparison.right.markMoves(result.rightMoves());
                            comparison.movePairs = result.moves();
                            comparison.methodChanges = result.methods();
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
                String reason = unavailable(e, startedNanos);
                Activator.logInfo("Structural BSL comparison unavailable: " + reason); //$NON-NLS-1$
                dispatch(display, () -> {
                    if (!comparison.root.isDisposed()) fallback.accept(reason);
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

    static String unavailable(Exception error, long startedNanos)
    {
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        String detail = error.getMessage() == null ? error.toString() : error.getMessage();
        return org.eclipse.osgi.util.NLS.bind(Messages.BslDiff_FallbackDetails,
            new Object[] {Messages.BslDiff_Unavailable, detail, Long.toString(elapsedMillis)});
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
        if (changedRows.isEmpty() && before.equals(after)) summary.setText(Messages.BslDiff_Identical);
        else if (changedRows.isEmpty() && byteChanges) summary.setText(Messages.BslDiff_FormattingOnly);
        if (fallbackReason != null) summary.setText(summary.getText() + " — " + fallbackReason);
        summary.getParent().layout(true, true);
        if (changed) navigate(true);
        search.refresh();
    }

    private void render(Result result)
    {
        left.show(result, true);
        right.show(result, false);
        overview.show(result, movePairs, methodChanges);
        navigation.show(BslMethodNavigation.entries(result, methodChanges,
            fallbackReason == null ? navigationFallback : fallbackReason));
        searchRows = result.rows();
    }

    private void navigate(boolean forward) { overview.navigate(forward); }

    private void openMethod(MethodAwareBslDiff.MethodChange method)
    {
        if (BslMethodNavigation.canCompare(method))
            org.eclipse.compare.CompareUI.openCompareEditor(new BslMethodCompareInput(method, before, after));
    }

    static String methodRange(List<Span> spans, int offset)
    {
        return spans.isEmpty() ? "—" : (spans.get(0).line() + offset + 1) + "–" //$NON-NLS-1$ //$NON-NLS-2$
            + (spans.get(spans.size() - 1).line() + offset + 1);
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
        final int lineOffset;
        final Canvas overview;
        final List<String> lines;
        final Map<Integer, List<Lexeme>> lexical = new HashMap<>();
        final Map<Integer, List<Span>> changes = new HashMap<>();
        final java.util.Set<Integer> moves = new java.util.HashSet<>();
        List<Integer> sourceLines = List.of();

        Side(Composite parent, String title, String source, Injector injector, boolean added, int baseLineOffset) throws Exception
        {
            this.lineOffset = baseLineOffset;
            lines = StructuralBslDiff.lines(source);
            Composite panel = new Composite(parent, SWT.NONE);
            panel.setLayout(new GridLayout(1, false));
            new Label(panel, SWT.NONE).setText(source.isEmpty() ? title + " — " + Messages.BslDiff_MethodAbsent : title); //$NON-NLS-1$
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
            final int numberWidth;
            var gutterGc = new org.eclipse.swt.graphics.GC(text);
            try
            {
                gutterGc.setFont(text.getFont());
                numberWidth = gutterGc.stringExtent(Integer.toString(Math.max(1, lines.size() + lineOffset))).x;
                int markerWidth = Math.max(gutterGc.stringExtent(" +").x, BslMoveIcon.size(text.getLineHeight()) + 8); //$NON-NLS-1$
                text.setMargins(Math.max(48, numberWidth + markerWidth + 18), 4, 4, 4);
            }
            finally { gutterGc.dispose(); }
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
                boolean wholeLine = wholeLineChanged(event.lineText, spans);
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
                            dark ? color(darkForeground(category))
                                : attribute == null || attribute.getForeground() == null ? text.getForeground() : attribute.getForeground(),
                            changed && !wholeLine ? color(dark ? (added ? DARK_ADDED : DARK_REMOVED)
                                : added ? new RGB(222, 239, 225) : new RGB(250, 222, 220)) : null,
                            attribute == null ? SWT.NORMAL : attribute.getStyle() & (SWT.BOLD | SWT.ITALIC));
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
                    event.lineBackground = color(dark ? (added ? new RGB(24, 40, 29) : new RGB(45, 28, 30))
                        : added ? new RGB(240, 249, 241) : new RGB(255, 242, 241));
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
                    event.gc.setFont(text.getFont());
                    event.gc.setForeground(changed
                        ? color(dark ? (added ? new RGB(112, 230, 137) : new RGB(255, 132, 132))
                            : added ? new RGB(0, 80, 20) : new RGB(138, 20, 20))
                        : dark ? color(new RGB(150, 150, 150)) : color(new RGB(85, 85, 85)));
                    int markerX = gutter - 7 - numberWidth - event.gc.stringExtent(" +").x; //$NON-NLS-1$
                    event.gc.drawString(changed ? (added ? "+" : "−") : "", markerX, y, true);
                    String number = Integer.toString(original + 1 + lineOffset);
                    event.gc.drawString(number, gutter - 7 - event.gc.stringExtent(number).x, y, true);
                    if (changed || moved)
                    {
                        if (moved && !changed) event.gc.setBackground(color(dark ? new RGB(115, 151, 197) : new RGB(126, 159, 201)));
                        else event.gc.setBackground(event.gc.getForeground());
                        event.gc.fillRectangle(gutter - 4, y, 2, height);
                    }
                    if (!changed || wholeLineChanged(lines.get(original), changes.get(original))) continue;
                    event.gc.setForeground(dark ? color(darkForeground(null)) : text.getForeground());
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
