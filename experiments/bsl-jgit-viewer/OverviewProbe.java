package com.ditrix.edt.mcp.server.ui;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;

/** Test-only real SWT interactions, confined to the runner's disposable EDT/Xvfb. */
public class OverviewProbe extends ViewerProbe
{
    @Override void run() throws Exception
    {
        for(int i=0;i<600&&!ui(()->PlatformUI.getWorkbench().getActiveWorkbenchWindow()!=null);i++)Thread.sleep(100);
        if(Boolean.getBoolean("viewer.probe.demo"))
        {
            ui(()->{
                demoFont();
                Shell shell=new Shell(display);shell.setText("BSL diff — Java / SWT");
                shell.setLayout(new FillLayout());shell.setSize(1480,920);
                BslStructuralCompare.create(shell,Files.readString(out.resolve("overview-before.bsl")),
                    Files.readString(out.resolve("overview-after.bsl")),null,reason->shell.setText(reason));
                shell.open();return null;
            });
            report.put("demoOpened",true);return;
        }
        corpus();
        String header="// Предпросмотр изменений BSL\n// Выравнивание строк и общая полоса прокрутки\n\n";
        String move="Функция ПодготовитьПараметры()\n\tПараметры = Новый Структура;\n\tПараметры.Вставить(\"Режим\", \"Проверка\");\n\tПараметры.Вставить(\"Повтор\", 3);\n\tВозврат Параметры;\nКонецФункции\n\n";
        String deleted="Процедура СтароеУведомление()\n\tТекст = \"Устаревший режим\";\n\tСообщить(Текст);\n\tСообщить(\"Операция завершена\");\nКонецПроцедуры\n\n";
        StringBuilder middle=new StringBuilder(),tail=new StringBuilder();
        for(int i=1;i<=11;i++)middle.append(method("ОбработатьЭтап"+i,i));
        for(int i=12;i<=22;i++)tail.append(method("ОбработатьЭтап"+i,i));
        String editedA="Процедура ЗаписатьРезультат()\n\tСообщить(\"Готово\");\nКонецПроцедуры\n\n";
        String editedB="Процедура ЗаписатьРезультат()\n\tТекст = \"Обработка завершена\";\n\tСообщить(Текст);\n\tСообщить(\"Результат сохранён\");\nКонецПроцедуры\n\n";
        String common=method("ПрочитатьДанные",0),continuation=method("ПродолжитьРаботу",7);
        String a=header+move+common+deleted+continuation+editedA+middle+tail;
        String b=header+common+continuation+editedB+middle+move+method("НоваяПроверка",42)+tail;
        Files.writeString(out.resolve("overview-before.bsl"),a);Files.writeString(out.resolve("overview-after.bsl"),b);
        var comparison=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
        require(comparison.moves().size()==1,"One paired method move");
        ui(()->{demoFont();return null;});
        viewer(a,b,"overview-review",true,false);
        ui(()->{
            Shell shell=new Shell(display);shell.setText("BSL diff — Java / SWT");
            shell.setLayout(new FillLayout());shell.setSize(1480,920);
            var constructor=BslStructuralCompare.class.getDeclaredConstructor(Composite.class,String.class,String.class);
            constructor.setAccessible(true);Object viewer=constructor.newInstance(shell,a,b);
            Composite root=(Composite)field(viewer,"root");
            try
            {
                set(viewer,"movePairs",comparison.moves());
                for(String name:List.of("left","right"))call(field(viewer,name),"markMoves",name.equals("left")?comparison.leftMoves():comparison.rightMoves());
                call(viewer,"show",comparison.diff());shell.open();shell.layout(true,true);display.update();
                StyledText left=(StyledText)field(field(viewer,"left"),"text"),right=(StyledText)field(field(viewer,"right"),"text");
                require((left.getVerticalBar()==null||!left.getVerticalBar().isVisible())
                    &&(right.getVerticalBar()==null||!right.getVerticalBar().isVisible()),"Shared integrated scrollbar replaces both native vertical bars");
                verifySource(viewer,a,b);
                require(left.getLineCount()==right.getLineCount()&&left.getLineCount()>StructuralBslDiff.lines(a).size(),"Aligned panes retain padding");
                Object nav=field(viewer,"overview");Canvas bar=(Canvas)field(nav,"bar");
                @SuppressWarnings("unchecked") List<Object> changes=(List<Object>)field(nav,"changes");
                Object moved=changes.stream().filter(c->!((String)get(c,"id")).isEmpty()).findFirst().orElseThrow();
                Object deletion=changes.stream().filter(c->((int)get(c,"kind"))==1).findFirst().orElseThrow();
                call(nav,"select",moved);synced(left,right);capture(root,"overview-move-light.png");
                int peer=(int)get(moved,"peer");
                @SuppressWarnings("unchecked") List<Object> hits=(List<Object>)field(nav,"leftHits");
                require(!hits.isEmpty(),"Move badge has a visible click target");
                Rectangle badge=(Rectangle)get(hits.get(0),"area");
                Event badgeClick=new Event();badgeClick.button=1;badgeClick.x=badge.x+2;badgeClick.y=badge.y+2;
                left.notifyListeners(SWT.MouseDown,badgeClick);synced(left,right);
                require(visible(right,peer),"Move destination reached without losing alignment");
                capture(root,"overview-move-target.png");
                call(nav,"select",deletion);synced(left,right);capture(root,"overview-delete-light.png");
                int rows=right.getLineCount(),height=bar.getClientArea().height;
                Event click=new Event();click.button=1;click.x=3;click.y=(int)Math.round((double)(int)get(deletion,"start")/rows*height);
                bar.notifyListeners(SWT.MouseDown,click);bar.notifyListeners(SWT.MouseUp,click);
                require(field(nav,"selected")==deletion,"Scrollbar marker click selects deletion hunk");
                call(nav,"jump",0);
                Rectangle thumb=(Rectangle)call(nav,"thumb");
                Event down=new Event();down.button=1;down.x=bar.getClientArea().width/2;down.y=thumb.y+thumb.height/2;
                bar.notifyListeners(SWT.MouseDown,down);
                Event drag=new Event();drag.stateMask=SWT.BUTTON1;drag.x=down.x;drag.y=height-1;
                bar.notifyListeners(SWT.MouseMove,drag);bar.notifyListeners(SWT.MouseUp,drag);
                require(right.getTopPixel()>right.getLineHeight()*80,"Dragging the thumb reaches the bottom");synced(left,right);
                Event home=new Event();home.keyCode=SWT.HOME;bar.notifyListeners(SWT.KeyDown,home);
                require(right.getTopPixel()==0,"Scrollbar Home reaches beginning");
                Event end=new Event();end.keyCode=SWT.END;bar.notifyListeners(SWT.KeyDown,end);
                require(right.getTopPixel()>0,"Scrollbar End reaches EOF");synced(left,right);
                Event wheel=new Event();wheel.count=2;int old=right.getTopPixel();left.notifyListeners(SWT.MouseWheel,wheel);
                require(right.getTopPixel()<old,"Wheel over left pane scrolls immediately");synced(left,right);
                right.invokeAction(ST.TEXT_END);right.notifyListeners(SWT.KeyUp,new Event());synced(left,right);
                Button next=controls(root,Button.class).stream().filter(c->c.getText().equals(Messages.BslDiff_Next)).findFirst().orElseThrow();
                Set<Object> visited=new HashSet<>();
                for(int i=0;i<changes.size();i++){next.notifyListeners(SWT.Selection,new Event());visited.add(field(nav,"selected"));synced(left,right);}
                require(visited.size()==changes.size(),"Next visits each hunk and move endpoint once and wraps");
                int offset=left.getText().indexOf('\t');left.setSelection(offset,offset+1);
                require(left.getSelectionText().equals("\t"),"Selection preserves actual tabs");left.setSelection(0);
                Combo theme=controls(root,Combo.class).get(0);theme.select(1);theme.notifyListeners(SWT.Selection,new Event());
                call(nav,"select",moved);capture(root,"overview-move-dark.png");
                shell.setSize(1050,700);shell.layout(true,true);call(nav,"select",deletion);synced(left,right);
                capture(root,"overview-delete-small-dark.png");
                report.put("overview",Map.of("rows",rows,"hunks",changes.size(),"moveEndpoints",2,"checks",List.of(
                    "complete aligned source and original numbers","one scrollbar with markers and draggable thumb","move endpoint jumps preserve alignment",
                    "marker click","drag to bottom","Home End","wheel both panes","native keyboard","hunk navigation wraps","real tab selection","themes and resize")));
            }
            finally{shell.dispose();}
            return null;
        });
        viewer("Процедура Удалить()\n\tСообщить(\"A\");\nКонецПроцедуры\n","","overview-empty-right",false,false);
        viewer("","Процедура Добавить()\n\tСообщить(\"A\");\nКонецПроцедуры\n","overview-empty-left",false,false);
        viewer("Процедура Пример()\n\tfoo = ;\nКонецПроцедуры\n","Процедура Пример()\n\t  foo = ;\nКонецПроцедуры\n","overview-whitespace",true,false);
        formattingOnly();
        viewer("//"+" ".repeat(250000)+"A","//"+" ".repeat(250000)+"B","overview-long-line",false,false);
        for(int i=0;i<3;i++)viewer(a.repeat(10),b.repeat(10),"overview-cancel-"+i,false,true);
        oversizedFallback();
        regressions();
    }
    void formattingOnly() throws Exception
    {
        String a="Процедура Пример()\nСообщить(1);\nКонецПроцедуры\n";
        String b="Процедура Пример()\n\tСообщить( 1 );\nКонецПроцедуры\n";
        var comparison=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
        ui(()->{
            Shell shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(1000,700);
            var constructor=BslStructuralCompare.class.getDeclaredConstructor(Composite.class,String.class,String.class);
            constructor.setAccessible(true);Object viewer=constructor.newInstance(shell,a,b);
            try
            {
                set(viewer,"byteChanges",comparison.byteChanges());call(viewer,"show",comparison.diff());
                Composite root=(Composite)field(viewer,"root");
                require(controls(root,Label.class).stream().anyMatch(l->l.getText().equals(Messages.BslDiff_FormattingOnly)),"Formatting-only notice remains visible");
                require(controls(root,Button.class).stream().noneMatch(Button::getEnabled),"Formatting-only changes have no structural navigation marks");
            }
            finally{shell.dispose();}
            report.put("formattingOnly",true);return null;
        });
    }
    void regressions() throws Exception
    {
        ui(()->{
            Shell shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(1000,700);
            var constructor=BslStructuralCompare.class.getDeclaredConstructor(Composite.class,String.class,String.class);
            constructor.setAccessible(true);
            String line="Сообщить(1);";
            String a=String.join("\n",Collections.nCopies(200,line)),b="";
            Object viewer=constructor.newInstance(shell,a,b);Composite root=(Composite)field(viewer,"root");
            try
            {
                List<StructuralBslDiff.Row> rows=new ArrayList<>();List<StructuralBslDiff.Span> spans=new ArrayList<>();
                for(int i=0;i<200;i++){rows.add(new StructuralBslDiff.Row(i,null));spans.add(new StructuralBslDiff.Span(i,0,line.length()));}
                call(viewer,"show",new StructuralBslDiff.Result("Text","changed",rows,spans,List.of()));
                shell.open();shell.layout(true,true);display.update();
                Object nav=field(viewer,"overview");Canvas bar=(Canvas)field(nav,"bar");
                set(nav,"selected",null);
                Event click=new Event();click.button=1;click.x=2;click.y=bar.getClientArea().height/2;
                bar.notifyListeners(SWT.MouseDown,click);bar.notifyListeners(SWT.MouseUp,click);
                report.put("tallMarkClickable",field(nav,"selected")!=null);
            }
            finally{shell.dispose();}
            // A valid moved endpoint with a declaration wider than the viewport.
            shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(820,650);
            a="Процедура Длинная("+String.join(", ",Collections.nCopies(60,"Параметр"))+")\nКонецПроцедуры";
            b="\n"+a;
            viewer=constructor.newInstance(shell,a,b);root=(Composite)field(viewer,"root");
            try
            {
                var oldSpan=List.of(new StructuralBslDiff.Span(0,0,a.indexOf('\n')));
                var newSpan=List.of(new StructuralBslDiff.Span(1,0,a.indexOf('\n')));
                set(viewer,"movePairs",List.of(new MethodAwareBslDiff.Move("procedure:длинная",oldSpan,newSpan)));
                call(viewer,"show",new StructuralBslDiff.Result("BSL","changed",List.of(
                    new StructuralBslDiff.Row(0,null),new StructuralBslDiff.Row(1,0),
                    new StructuralBslDiff.Row(null,1),new StructuralBslDiff.Row(null,2)),List.of(),List.of()));
                shell.open();shell.layout(true,true);display.update();
                Object nav=field(viewer,"overview");
                capture(root,"regression-long-header.png");
                StyledText text=(StyledText)field(field(viewer,"left"),"text");
                report.put("longHeaderDebug",Map.of("top",text.getTopPixel(),"rowPixel",text.getLinePixel(0),
                    "width",text.getClientArea().width,"height",text.getClientArea().height,"margin",text.getLeftMargin(),
                    "changes",String.valueOf(field(nav,"changes")),"hits",String.valueOf(field(nav,"leftHits"))));
                report.put("longHeaderBadgeVisible",!((List<?>)field(nav,"leftHits")).isEmpty());
            }
            finally{shell.dispose();}
            return null;
        });
        require(Boolean.TRUE.equals(report.get("tallMarkClickable")),"Middle of a tall painted mark selects its hunk");
        require(Boolean.TRUE.equals(report.get("longHeaderBadgeVisible")),"Long method header retains a visible move badge target");
    }
    static String method(String name,int n){return "Процедура "+name+"()\n\tНомер = "+n+";\n\tТекст = \"Этап обработки\";\n\tСообщить(Текст);\nКонецПроцедуры\n\n";}
    static void demoFont(){JFaceResources.getFontRegistry().put(JFaceResources.TEXT_FONT,new FontData[]{new FontData("Monospace",12,SWT.NORMAL)});}
    static boolean visible(StyledText t,int row){int y=t.getLinePixel(row);return y>=0&&y<t.getClientArea().height;}
    static void synced(StyledText l,StyledText r){require(Math.abs(l.getTopPixel()-r.getTopPixel())<=1,"Both panes keep identical aligned scroll position");}
    static void verifySource(Object viewer,String a,String b)throws Exception
    {
        for(String name:List.of("left","right"))
        {
            Object side=field(viewer,name);StyledText text=(StyledText)field(side,"text");
            List<String> lines=StructuralBslDiff.lines(name.equals("left")?a:b);
            @SuppressWarnings("unchecked") List<Integer> numbers=(List<Integer>)field(side,"sourceLines");
            int count=0;for(int row=0;row<numbers.size();row++)if(numbers.get(row)!=null)
            {require(numbers.get(row)==count,"Original source numbering");require(text.getLine(row).equals(lines.get(count++)),"Exact source line including whitespace");}
            require(count==lines.size(),"Every source line retained");
        }
    }
    static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object v)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,v);}
    static Object get(Object o,String name){try{return field(o,name);}catch(Exception e){throw new RuntimeException(e);}}
    static Object call(Object o,String name,Object... values)throws Exception
    {for(Method m:o.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==values.length){m.setAccessible(true);return m.invoke(o,values);}throw new NoSuchMethodException(name);}
}
