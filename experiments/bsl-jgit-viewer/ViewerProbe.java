package com.ditrix.edt.mcp.server.ui;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import com.google.gson.*;
import com.ditrix.edt.mcp.server.utils.PreparedBslWrite;
import com.ditrix.edt.mcp.server.utils.BslWriteReviews;
import org.eclipse.compare.CompareUI;
import java.nio.charset.StandardCharsets;

/** Test-only fragment. No writes or automatic approval of a real review. */
public class ViewerProbe implements IStartup
{
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    final Path out = Path.of(System.getProperty("viewer.probe.output"));
    final Display display = PlatformUI.getWorkbench().getDisplay();
    final List<Object> cycles = new ArrayList<>();
    final List<WeakReference<Composite>> roots = new ArrayList<>();
    final Map<String,Object> report = new LinkedHashMap<>();

    @Override public void earlyStartup()
    {
        Thread worker = new Thread(() -> {
            try { run(); report.put("passed", true); }
            catch (Throwable error)
            {
                report.put("passed", false); report.put("failure", error.toString());
                java.io.StringWriter trace = new java.io.StringWriter();
                error.printStackTrace(new java.io.PrintWriter(trace)); report.put("trace", trace.toString());
            }
            finally
            {
                try { Files.writeString(out.resolve("viewer-results.json"), JSON.toJson(report)); }
                catch (Exception error) { error.printStackTrace(); }
            }
        }, "BSL viewer validation");
        worker.setDaemon(true); worker.start();
    }

    void run() throws Exception
    {
        for (int i=0; i<600 && !ui(() -> PlatformUI.getWorkbench().getActiveWorkbenchWindow()!=null); i++) Thread.sleep(100);
        ui(() -> { display.setTracking(true); return null; });
        report.put("warmWorkbenchBeforeFirstUse", snapshot());
        var fixtures=JsonParser.parseString(Files.readString(Path.of(System.getProperty("viewer.probe.input")))).getAsJsonArray();
        JsonObject sample=null;
        for(var item:fixtures)if(item.getAsJsonObject().get("id").getAsString().equals("move-edit"))sample=item.getAsJsonObject();
        require(sample!=null,"Recorded move-edit fixture exists");
        String a=sample.get("before").getAsString(),b=sample.get("after").getAsString();
        long started = System.nanoTime();
        viewer(a, b, "first-use", true, false);
        report.put("firstUseOpenComputeDisposeMs", (System.nanoTime()-started)/1e6);
        var result=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
        require(result.fallback()==null && !result.leftMoves().isEmpty(),"Valid method-aware move fixture");
        corpus();
        oversizedFallback();
        viewer("Процедура Пример()\n\tfoo = ;\nКонецПроцедуры\n",
            "Процедура Пример()\n\t  foo = ;\nКонецПроцедуры\n","whitespace",true,false);
        long paintStart=System.nanoTime();
        viewer("//"+" ".repeat(250000)+"A", "//"+" ".repeat(250000)+"B", "long-line",false,false);
        report.put("longLineOpenComputePaintDisposeMs",(System.nanoTime()-paintStart)/1e6);
        Thread.sleep(15000); // Let unrelated EDT startup dialogs and service initialization settle.
        for (int i=0;i<5;i++) viewer(a,b,"warm-"+i,false,false);
        report.put("warmViewerBaseline", snapshot());
        for (int i=0;i<100;i++)
        {
            viewer(a,b,"cycle-"+i,false,false);
            if (i%10==9) cycles.add(snapshot());
        }
        report.put("cycleCheckpoints",cycles);
        for (int i=0;i<20;i++) viewer(a.repeat(30), b.repeat(30),"cancel-"+i,false,true);
        Thread.sleep(1000);
        report.put("afterDisposedWhileComputing", snapshot());
        report.put("beforeRequestedGc", snapshot());
        viewer(a,b,"post-cancel-worker-reuse",false,false);
        ui(()->null);
        // Eclipse worker stacks may retain their last completed Job until the worker runs again.
        for(int i=0;i<32;i++)
        {
            var idle=org.eclipse.core.runtime.jobs.Job.create("Viewer probe worker reuse",m->org.eclipse.core.runtime.Status.OK_STATUS);
            idle.setSystem(true);idle.schedule();idle.join();
        }
        System.gc(); Thread.sleep(400);
        report.put("afterRequestedGc", snapshot());
        long live=roots.stream().filter(r->r.get()!=null).count();
        report.put("survivingViewerRoots",live);
        List<Integer> surviving=new ArrayList<>();
        for(int i=0;i<roots.size();i++)if(roots.get(i).get()!=null)surviving.add(i);
        report.put("survivingViewerIndexes",surviving);
        require(live==0,"Disposed viewer controls retained after diagnostic GC: "+surviving);
        // A real read-only review remains in the isolated display. Never press Apply.
        viewer(a,b,"inspect",true,false,true);
    }

    void corpus() throws Exception
    {
        var inputs=JsonParser.parseString(Files.readString(Path.of(System.getProperty("viewer.probe.input")))).getAsJsonArray();
        var expected=JsonParser.parseString(Files.readString(Path.of(System.getProperty("viewer.probe.expected")))).getAsJsonArray();
        List<Object> evidence=new ArrayList<>();
        for(int n=0;n<inputs.size();n++)
        {
            JsonObject item=inputs.get(n).getAsJsonObject(), want=expected.get(n).getAsJsonObject();
            String a=item.get("before").getAsString(),b=item.get("after").getAsString();
            var actual=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
            require(JSON.toJsonTree(actual.diff().left()).equals(want.get("left")),"left ranges "+item.get("id"));
            require(JSON.toJsonTree(actual.diff().right()).equals(want.get("right")),"right ranges "+item.get("id"));
            require(JSON.toJsonTree(actual.leftMoves()).equals(want.get("leftMoves")),"left moves "+item.get("id"));
            require(JSON.toJsonTree(actual.rightMoves()).equals(want.get("rightMoves")),"right moves "+item.get("id"));
            require(Objects.equals(actual.fallback(),want.get("fallback").isJsonNull()?null:want.get("fallback").getAsString()),"fallback "+item.get("id"));
            require(actual.byteChanges()==!a.equals(b),"exact bytes "+item.get("id"));
            int l=0,r=0;
            for(var row:actual.diff().rows())
            {
                if(row.left()!=null) require(row.left()==l++,"left coverage "+item.get("id"));
                if(row.right()!=null) require(row.right()==r++,"right coverage "+item.get("id"));
            }
            require(l==StructuralBslDiff.lines(a).size()&&r==StructuralBslDiff.lines(b).size(),"complete lines "+item.get("id"));
            evidence.add(Map.of("id",item.get("id").getAsString(),"rows",actual.diff().rows().size(),"fallback",String.valueOf(actual.fallback())));
        }
        report.put("corpus",evidence);
    }

    void viewer(String a,String b,String id,boolean capture,boolean cancel) throws Exception
    { viewer(a,b,id,capture,cancel,false); }

    void viewer(String a,String b,String id,boolean capture,boolean cancel,boolean leave) throws Exception
    {

        var reviews=BslWriteReviews.shared();
        byte[] bytes=b.getBytes(StandardCharsets.UTF_8);
        var write=new PreparedBslWrite("ViewerFixture","file:/viewer-fixture",0,"Module.bsl","file:/viewer-fixture/Module.bsl",
            true,PreparedBslWrite.sha256(a.getBytes(StandardCharsets.UTF_8)),a,b,bytes,
            StructuralBslDiff.lines(a).size(),StructuralBslDiff.lines(b).size(),"replace",false,null);
        var admission=reviews.register(write,id,(key,value)->{throw new AssertionError("Review must never be accepted automatically");});
        require(admission.snapshot()!=null,"Review admitted");
        String reviewId=admission.snapshot().reviewId();
        BslWriteReviewInput input=ui(()->{
            var page=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
            var review=new BslWriteReviewInput(write,reviewId,reviews);
            CompareUI.openCompareEditorOnPage(review,page);
            return review;
        });
        Composite shell=ui(()->(Composite)input.created().getNow(null).get());
        require(shell!=null,"Review controls exist");
        roots.add(new WeakReference<>(shell));
        if(!cancel)
        {
            boolean completed=false;
            for(int i=0;i<1000;i++)
            {

                if(ui(()->controls(shell,Label.class).stream().noneMatch(l->l.getText().equals(Messages.BslDiff_Computing))))
                { completed=true;break; }
                Thread.sleep(10);
            }
            require(completed,"Viewer did not complete: "+id);
            ui(()->{
                List<StyledText> texts=controls(shell,StyledText.class);
                require(texts.size()==2,"Two native text panes");
                require(controls(shell,Combo.class).stream().anyMatch(combo->combo.getItemCount()==2
                    && combo.getItem(0).equals(Messages.BslDiff_Light)),"Method viewer toolbar exists on every cycle");
                for(StyledText text:texts)require((text.getStyle()&SWT.READ_ONLY)!=0,"Read-only pane");
                if(capture)
                {
                    capture(shell,id+"-light.png");
                    var theme=controls(shell,Combo.class).get(0);
                    theme.select(1);theme.notifyListeners(SWT.Selection,new Event());
                    capture(shell,id+"-dark.png");
                    theme.select(0);theme.notifyListeners(SWT.Selection,new Event());
                    String content=texts.get(0).getText();
                    String wanted=content.contains("\t")?"\t":"\"A\"";
                    int pos=content.indexOf(wanted);
                    require(pos>=0,"Original selection fixture remains visible");
                    texts.get(0).setSelection(pos,pos+wanted.length());
                    require(texts.get(0).getSelectionText().equals(wanted),"Native selection returns original text");
                    texts.get(0).setSelection(0);
                }
                return null;
            });
        }
        require(reviews.get(reviewId).status()==BslWriteReviews.State.AWAITING_APPROVAL,"Review stays pending");
        if(!leave)
        {
            ui(()->{
                var editor=(IEditorPart)input.getContainer().getWorkbenchPart();
                editor.getSite().getPage().closeEditor(editor,false);
                return null;
            });
            require(reviews.get(reviewId).status()==BslWriteReviews.State.REJECTED,"Closing rejects without a write");
        }
    }

    void oversizedFallback() throws Exception
    {
        ui(()->{
            String large="//"+"x".repeat(524287);
            var write=new PreparedBslWrite("OversizedFixture","file:/oversized",0,"Module.bsl","file:/oversized/Module.bsl",
                true,"hash","",large,large.getBytes(StandardCharsets.UTF_8),0,1,"replace",false,null);
            var reviews=BslWriteReviews.shared();
            String id=reviews.register(write,"oversized",(key,value)->{throw new AssertionError("No automatic acceptance");}).snapshot().reviewId();
            Shell shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(1000,700);
            BslWriteReviewInput input=new BslWriteReviewInput(write,id,reviews);
            try
            {
                var root=(Composite)input.createContents(shell);
                require(controls(root,Label.class).stream().anyMatch(label->label.getText().contains("524288")),
                    "Oversized fallback explains its exact input limit");
                require(reviews.get(id).status()==BslWriteReviews.State.AWAITING_APPROVAL,"Fallback does not accept proposal");
            }
            finally{shell.dispose();input.handleDispose();}
            return null;
        });
        report.put("oversizedInputNoticePassed",true);
    }

    void capture(Control control,String name) throws Exception
    {
        control.getShell().layout(true,true);control.redraw();display.update();
        var size=control.getSize();Image image=new Image(display,size.x,size.y);GC gc=new GC(image);
        try
        {
            if (!control.print(gc)) { report.put("widgetCapture", "SWT Control.print is unavailable on this backend"); return; }
            ImageLoader loader=new ImageLoader();loader.data=new ImageData[]{image.getImageData()};loader.save(out.resolve(name).toString(),SWT.IMAGE_PNG);
        }
        finally{gc.dispose();image.dispose();}
    }

    Map<String,Object> snapshot() throws Exception
    {
        Map<String,Object> result=new LinkedHashMap<>();
        var memory=ManagementFactory.getMemoryMXBean();
        result.put("heapUsed",memory.getHeapMemoryUsage().getUsed());result.put("heapCommitted",memory.getHeapMemoryUsage().getCommitted());
        result.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());
        result.put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionCount())).sum());
        result.put("gcTimeMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionTime())).sum());
        try(var f=Files.list(Path.of("/proc/self/fd"))){result.put("fds",f.count());}
        for(String line:Files.readAllLines(Path.of("/proc/self/status")))
            if(line.startsWith("VmRSS:")||line.startsWith("VmHWM:")) result.put(line.substring(0,line.indexOf(':')),line.substring(line.indexOf(':')+1).strip());
        result.put("swt",ui(()->{
            Map<String,Integer> counts=new TreeMap<>();
            for(Object o:display.getDeviceData().objects)if(o!=null)counts.merge(o.getClass().getSimpleName(),1,Integer::sum);
            counts.put("Controls",Arrays.stream(display.getShells()).mapToInt(s->controls(s,Control.class).size()).sum());
            report.put("latestShellTitles",Arrays.stream(display.getShells()).filter(Shell::isVisible).map(Shell::getText).toList());
            return counts;
        }));
        return result;
    }

    interface Checked<T>{T run()throws Exception;}
    <T>T ui(Checked<T> action)throws Exception
    {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        display.syncExec(()->{try{result.set(action.run());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new Exception(error.get());return result.get();
    }
    static <T>List<T> controls(Composite root,Class<T> type)
    {
        List<T> result=new ArrayList<>();
        for(Control c:root.getChildren())
        {if(type.isInstance(c))result.add(type.cast(c));if(c instanceof Composite child)result.addAll(controls(child,type));}
        return result;
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
