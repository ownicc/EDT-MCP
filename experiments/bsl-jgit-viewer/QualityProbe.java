package com.ditrix.edt.mcp.server.ui;

import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.TextAttribute;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.eclipse.compare.CompareEditorInput;
import com.google.gson.*;

/** Real EDT/SWT assertions for the approved quality change; runner confines this to Xvfb. */
public class QualityProbe extends ViewerProbe
{
    @Override void run() throws Exception
    {
        for(int i=0;i<600&&!ui(()->PlatformUI.getWorkbench().getActiveWorkbenchWindow()!=null);i++)Thread.sleep(100);
        List<String> corrected=new ArrayList<>();
        JsonArray inputs=JsonParser.parseString(Files.readString(out.resolve("input.json"))).getAsJsonArray();
        JsonArray expected=JsonParser.parseString(Files.readString(out.resolve("expected.json"))).getAsJsonArray();
        int index=0;
        for(JsonElement entry:inputs)
        {
            JsonObject item=entry.getAsJsonObject(), old=expected.get(index++).getAsJsonObject();
            String a=item.get("before").getAsString(),b=item.get("after").getAsString();
            var result=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
            int l=0,r=0;
            for(var row:result.diff().rows())
            {
                if(row.left()!=null)require(row.left()==l++,"Monotonic complete left source");
                if(row.right()!=null)require(row.right()==r++,"Monotonic complete right source");
            }
            require(l==StructuralBslDiff.lines(a).size()&&r==StructuralBslDiff.lines(b).size(),"Every physical source line retained");
            for(boolean left:List.of(true,false))
            {
                List<String> lines=StructuralBslDiff.lines(left?a:b);
                for(var span:left?result.diff().left():result.diff().right())
                    require(span.line()>=0&&span.line()<lines.size()&&span.start()>=0&&span.length()>=0
                        &&span.start()+span.length()<=lines.get(span.line()).length(),"Exact span remains inside source line");
            }
            if(!JSON.toJsonTree(result.diff().left()).equals(old.get("left"))
                ||!JSON.toJsonTree(result.diff().right()).equals(old.get("right"))
                ||!JSON.toJsonTree(result.moves()).equals(old.get("moves"))
                ||!Objects.equals(result.fallback(),old.get("fallback").isJsonNull()?null:old.get("fallback").getAsString()))
                corrected.add(item.get("id").getAsString());
        }
        report.put("corpusSourceCoverage",index);report.put("changedFrozenCases",corrected);
        String moved="// Документация перемещённого метода\nФункция Переносимый() Экспорт\n#Если Клиент Тогда\nВозврат 11;\n#Иначе\nВозврат 20;\n#КонецЕсли\nКонецФункции\n\n";
        String stable="Функция Стабильный() Экспорт\nВозврат 30;\nКонецФункции\n\n";
        String added="Функция НоваяФункция() Экспорт\n// Новый комментарий\nВозврат \"Новый текст\";\nКонецФункции\n\n";
        String a="#Область Один\n"+moved+"#КонецОбласти\n#Область Два\n"+stable+"#КонецОбласти\n";
        String b="#Область Один\n#КонецОбласти\n#Область Два\n"+stable+moved.replace("11","12")+added+"#КонецОбласти\n";
        var result=MethodAwareBslDiff.compute(a,b,new NullProgressMonitor());
        var identical=MethodAwareBslDiff.compute(stable,stable,new NullProgressMonitor());
        require(result.fallback()==null&&result.moves().size()==1,"Conditional moved+edited method paired exactly once");
        ui(()->{
            Shell shell=new Shell(display);shell.setLayout(new FillLayout());shell.setSize(1500,950);
            var constructor=BslStructuralCompare.class.getDeclaredConstructor(Composite.class,String.class,String.class);
            constructor.setAccessible(true);Object viewer=constructor.newInstance(shell,a,b);
            Composite root=(Composite)field(viewer,"root");
            try
            {
                set(viewer,"movePairs",result.moves());
                call(field(viewer,"left"),"markMoves",result.leftMoves());
                call(field(viewer,"right"),"markMoves",result.rightMoves());
                call(viewer,"show",result.diff());shell.open();shell.layout(true,true);display.update();
                Object side=field(viewer,"right");StyledText text=(StyledText)field(side,"text");
                @SuppressWarnings("unchecked") List<Integer> sourceLines=(List<Integer>)field(side,"sourceLines");
                @SuppressWarnings("unchecked") Map<Integer,List<Object>> lexical=(Map<Integer,List<Object>>)field(side,"lexical");
                for(boolean dark:List.of(false,true))
                {
                    Combo theme=controls(root,Combo.class).get(0);theme.select(dark?1:0);theme.notifyListeners(SWT.Selection,new Event());
                    require(text.getBackground().getRGB().equals(dark?BslStructuralCompare.DARK_BACKGROUND
                        :new org.eclipse.swt.graphics.RGB(255,255,255)),"Selected theme background must not inherit the opposite GTK theme");
                    int row=text.getLineAtOffset(text.getText().indexOf("Возврат \"Новый текст\""));
                    int original=sourceLines.get(row);
                    StyleRange[] styles=lineStyles(text,row);
                    require(styles.length>1,"Added code retains separate lexical styles");
                    for(StyleRange style:styles)
                    {
                        require(!style.underline,"Diff must not underline added code");
                        int column=style.start-text.getOffsetAtLine(row);
                        for(Object lexeme:lexical.getOrDefault(original,List.of()))
                            if((int)get(lexeme,"start")<=column&&column<(int)get(lexeme,"end"))
                            {
                                TextAttribute attribute=(TextAttribute)get(lexeme,"attribute");
                                var wanted=dark?BslStructuralCompare.darkForeground((String)get(lexeme,"category"))
                                    :attribute==null||attribute.getForeground()==null?text.getForeground().getRGB():attribute.getForeground().getRGB();
                                require(style.foreground!=null&&style.foreground.getRGB().equals(wanted),"Added token retains syntax foreground");
                                require(style.fontStyle==(attribute==null?SWT.NORMAL:attribute.getStyle()&(SWT.BOLD|SWT.ITALIC)),"Added token retains lexical font style");
                            }
                    }
                    capture(root,dark?"quality-dark.png":"quality-light.png");
                }
                report.put("syntaxPreservedBothThemes",true);
                int movedRow=text.getLineAtOffset(text.getText().indexOf("Функция Переносимый"));
                require(lineBackground(text,movedRow)==null,"Moved body has no full blue fill (variant A)");
                report.put("movedBodyUnfilled",true);
                Shell equalityShell=new Shell(display);equalityShell.setLayout(new FillLayout());
                try
                {
                    Object equality=constructor.newInstance(equalityShell,stable,stable);
                    call(equality,"show",identical.diff());
                    String summary=((Label)field(equality,"summary")).getText();
                    require(summary.equals("Texts are identical")||summary.equals("Тексты совпадают"),"Identical method bodies must not claim text or formatting differs");
                }
                finally{equalityShell.dispose();}
                report.put("identicalMethodNotice",true);
                Button compare=controls(root,Button.class).stream().filter(c->c.getText().equals("Сравнить метод")||c.getText().equals("Compare method")).findFirst().orElseThrow(()->new AssertionError("Compare method action exists"));
                Object nav=field(viewer,"overview");
                @SuppressWarnings("unchecked") List<Object> changes=(List<Object>)field(nav,"changes");
                Object origin=changes.stream().filter(c->!((String)get(c,"id")).isEmpty()&&(boolean)get(c,"left")).findFirst().orElseThrow();
                call(nav,"select",origin);display.update();require(compare.isEnabled(),"Selected move enables comparison action");
                @SuppressWarnings("unchecked") List<Object> hits=(List<Object>)field(nav,"leftHits");
                require(!hits.isEmpty(),"Move badge is visible");
                var badge=(org.eclipse.swt.graphics.Rectangle)get(hits.get(0),"area");
                StyledText left=(StyledText)field(field(viewer,"left"),"text");
                int declaration=left.getLineAtOffset(left.getText().indexOf("Функция Переносимый"));
                require(badge.y==left.getLinePixel(declaration),"Single move badge is beside declaration, not documentation: badge="+badge.y+", declaration="+left.getLinePixel(declaration)+", leftTop="+left.getTopPixel()+", rightTop="+text.getTopPixel());
                Event click=new Event();click.button=1;click.x=badge.x+2;click.y=badge.y+2;left.notifyListeners(SWT.MouseDown,click);
                require(left.getTopPixel()==text.getTopPixel(),"Paired jump retains aligned scrolling");
                report.put("pairedJump",true);
                compare.notifyListeners(SWT.Selection,new Event());
                var page=PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
                var editor=page.getActiveEditor();
                require(editor!=null&&editor.getEditorInput().getClass().getSimpleName().equals("BslMethodCompareInput"),"Action opens a separate method-only tab");
                CompareEditorInput input=(CompareEditorInput)editor.getEditorInput();
                require(!input.getCompareConfiguration().isLeftEditable()&&!input.getCompareConfiguration().isRightEditable(),"Method tab is read-only");
                require(!input.isDirty()&&!input.isSaveNeeded(),"Method tab cannot save a write");
                require(input.getTitle().toLowerCase(Locale.ROOT).contains("переносимый"),"Method tab identifies selected method");
                report.put("methodOnlyTabOpened",true);
                page.closeEditor(editor,false);
                call(nav,"select",origin);
                Object search=field(viewer,"search");Composite searchRow=(Composite)field(search,"row");
                Event findKey=new Event();findKey.keyCode='f';findKey.stateMask=SWT.MOD1;text.notifyListeners(ST.VerifyKey,findKey);
                require(searchRow.getVisible()&&!findKey.doit,"Ctrl+F opens inline search and consumes the key");
                Text query=(Text)field(search,"query");Combo scope=(Combo)field(search,"scope");Button matchCase=(Button)field(search,"matchCase");
                query.setText("возврат");
                require(((List<?>)field(search,"matches")).size()==7,"Both snapshots searched, including unchanged and moved lines");
                scope.select(1);scope.notifyListeners(SWT.Selection,new Event());
                require(((List<?>)field(search,"matches")).size()==4,"New scope searches only proposed source");
                require(text.getSelectionText().equals("Возврат"),"Match selects exact real source text after alignment");
                scope.select(2);scope.notifyListeners(SWT.Selection,new Event());
                Event back=new Event();back.keyCode=SWT.F3;back.stateMask=SWT.SHIFT;query.notifyListeners(SWT.KeyDown,back);
                require((int)field(search,"current")==6,"Shift+F3 wraps backwards across both versions");
                matchCase.setSelection(true);matchCase.notifyListeners(SWT.Selection,new Event());
                require(((List<?>)field(search,"matches")).isEmpty(),"Case-sensitive query with no results");
                matchCase.setSelection(false);matchCase.notifyListeners(SWT.Selection,new Event());
                capture(root,"quality-search.png");
                Event escape=new Event();escape.keyCode=SWT.ESC;query.notifyListeners(SWT.KeyDown,escape);
                require(!searchRow.getVisible(),"Escape closes inline search");
                call(search,"refresh");
                Event reopen=new Event();reopen.keyCode=SWT.F3;text.notifyListeners(ST.VerifyKey,reopen);
                require((int)field(search,"current")==0,"F3 after a hidden alignment refresh must select the first match once");
                call(search,"close");
                require(call(nav,"selectedMove")!=null&&compare.isEnabled(),"Search preserves selected move");
                require(!text.getEditable(),"Search does not enable source writes");
                report.put("inlineSearchBothScopesKeyboard",true);
            }
            finally{shell.dispose();}
            return null;
        });
        report.put("qualityPassed",true);
    }

    static Object lineBackground(StyledText text,int row)throws Exception
    {
        Class<?> type=Class.forName("org.eclipse.swt.custom.StyledTextEvent");
        var constructor=type.getDeclaredConstructor(StyledTextContent.class);constructor.setAccessible(true);
        Event event=(Event)constructor.newInstance(text.getContent());event.widget=text;
        event.detail=text.getOffsetAtLine(row);event.text=text.getLine(row);
        text.notifyListeners(ST.LineGetBackground,event);
        return field(event,"lineBackground");
    }
    static StyleRange[] lineStyles(StyledText text,int row)throws Exception
    {
        Class<?> type=Class.forName("org.eclipse.swt.custom.StyledTextEvent");
        var constructor=type.getDeclaredConstructor(StyledTextContent.class);constructor.setAccessible(true);
        Event event=(Event)constructor.newInstance(text.getContent());event.widget=text;
        event.detail=text.getOffsetAtLine(row);event.text=text.getLine(row);
        text.notifyListeners(ST.LineGetStyle,event);
        return (StyleRange[])field(event,"styles");
    }
    static Object field(Object value,String name)throws Exception {var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);}
    static void set(Object value,String name,Object content)throws Exception {var f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,content);}
    static Object get(Object value,String name){try{return value.getClass().getDeclaredMethod(name).invoke(value);}catch(Exception e){throw new RuntimeException(e);}}
    static Object call(Object value,String name,Object...args)throws Exception
    {for(var m:value.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(value,args);}throw new NoSuchMethodException(name);}
}
