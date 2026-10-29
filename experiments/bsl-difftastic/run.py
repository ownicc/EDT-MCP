#!/usr/bin/env python3
"""Throwaway BSL/difftastic experiment; Python stdlib only, no EDT mutation.

Writes generated fixtures, raw JSON, syntax dumps and a standalone preview to
--output. The binary must have the adjacent difftastic-bsl.patch applied.
"""

import argparse
import difflib
import hashlib
import html
import itertools
import json
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import time


def module(body, name="Проверка"):
    return f"Процедура {name}()\n{body}\nКонецПроцедуры\n"


CALL = module("    Сообщить(Объект.Код, 100);")
WRAPPED = module("    Сообщить(\n        Объект.Код,\n        100\n    );")
QUERY = module('    Запрос = Новый Запрос;\n    Запрос.Текст = "ВЫБРАТЬ\n    | Товары.Код\n    |ИЗ Справочник.Товары КАК Товары";')
METHOD_A = module('    Сообщить("A");', "Первый")
METHOD_B = module('    Сообщить("B");', "Второй")

# Expected results are about syntax, not a decision to apply exact output bytes.
CASES = [
    ("formatting", "Только переносы и отступы", CALL, WRAPPED, "unchanged"),
    ("expression", "Число изменено одновременно с переносом вызова", CALL, WRAPPED.replace("100", "120"), "changed"),
    ("nested-if", "Вложенное условие", CALL, module("    Если Объект.Код <> 0 Тогда\n        Если Объект.ПометкаУдаления Тогда\n            Возврат;\n        КонецЕсли;\n        Сообщить(Объект.Код, 100);\n    КонецЕсли;"), "changed"),
    ("move", "Перестановка целых процедур", METHOD_A + "\n" + METHOD_B, METHOD_B + "\n" + METHOD_A, "changed"),
    ("preprocessor", "Условие препроцессора", "#Если Клиент Тогда\n" + CALL + "#КонецЕсли\n", "#Если Сервер Тогда\n" + CALL + "#КонецЕсли\n", "changed"),
    ("region", "Добавление процедуры внутри области", "#Область Интерфейс\n" + METHOD_A + "#КонецОбласти\n", "#Область Интерфейс\n" + METHOD_A + METHOD_B + "#КонецОбласти\n", "changed"),
    ("annotation", "Директива компиляции", "&НаКлиенте\n" + CALL, "&НаСервере\n" + CALL, "changed"),
    ("extension", "Аннотация расширения и вставка", '&Перед("Проверка")\n' + CALL, '&После("Проверка")\n' + CALL.replace("    Сообщить", "#Вставка\n    Сообщить").replace("КонецПроцедуры", "#КонецВставки\nКонецПроцедуры"), "changed"),
    ("query", "Изменение текста запроса", QUERY, QUERY.replace("Товары.Код", "Товары.Наименование"), "changed"),
    ("query-space", "Значимый пробел внутри текста запроса", QUERY, QUERY.replace("ВЫБРАТЬ", "ВЫБРАТЬ "), "changed"),
    ("string-space", "Пробел внутри строки", module('    Сообщить("Код");'), module('    Сообщить(" Код");'), "changed"),
    ("unicode", "Кириллица, emoji и экранированные кавычки", module('    Сообщить("Привет 😀 ""мир""");'), module('    Сообщить("Привет 🌍 ""мир""");'), "changed"),
    ("comment", "Изменение комментария", module("    // Старое пояснение\n    Сообщить(1);"), module("    // Новое пояснение\n    Сообщить(1);"), "changed"),
    ("english", "Английский BSL и асинхронный вызов", "Async Procedure Check()\n    Result = Await Calculate(100);\nEndProcedure\n", "Async Procedure Check()\n    Result = Await Calculate(120);\nEndProcedure\n", "changed"),
    ("invalid", "Незавершённое условие: текстовый fallback", CALL, module("    Если Тогда\n    Сообщить(1);"), "changed"),
    ("invalid-string", "Незакрытая строка: текстовый fallback", CALL, module('    Сообщить("не закрыта);'), "changed"),
    ("invalid-paren", "Незакрытая скобка: текстовый fallback", CALL, module("    Сообщить(1;"), "changed"),
    ("new-file", "Создание модуля", "", CALL, "created"),
    ("delete-file", "Удаление модуля", CALL, "", "deleted"),
    ("line-endings", "Только LF → CRLF", CALL, CALL.replace("\n", "\r\n"), "unchanged"),
    ("bom", "Только добавление UTF-8 BOM", CALL, "\ufeff" + CALL, "unchanged"),
    ("case", "Только регистр ключевого слова", CALL, CALL.replace("Процедура", "ПРОЦЕДУРА"), "changed"),
]


def execute(binary, *args):
    # Remove user DFT_* overrides to keep this experiment reproducible.
    env = {k: v for k, v in os.environ.items() if not k.startswith("DFT_")}
    env["DFT_UNSTABLE"] = "yes"
    return subprocess.run([str(binary), *map(str, args)], env=env,
                          capture_output=True, text=True, encoding="utf-8",
                          check=True, timeout=30).stdout


def compare(binary, before, after):
    timings = []
    for _ in range(3):
        started = time.perf_counter()
        result = json.loads(execute(binary, "--display=json", before, after))
        timings.append((time.perf_counter() - started) * 1000)
    return result, round(statistics.median(timings), 3)


def tokens(result):
    return [change for chunk in result.get("chunks", []) for row in chunk
            for side in row.values() for change in side["changes"]]


def validate_ranges(result, texts):
    """Difftastic columns are UTF-8 byte offsets; GUI indexes must convert them."""
    for chunk in result.get("chunks", []):
        for row in chunk:
            for side_name, side in row.items():
                line = texts[side_name].split("\n")[side["line_number"]].encode()
                for change in side["changes"]:
                    assert line[change["start"]:change["end"]].decode() == change["content"]
    for left, right in result.get("aligned_lines", []):
        for side, line in (("lhs", left), ("rhs", right)):
            assert line is None or 0 <= line < len(texts[side].split("\n"))


NODE = re.compile(r"^\s*(\S+) \((\d+), (\d+)\) - \((\d+), (\d+)\)")


def syntax_ranges(dump, text):
    """Use the actual tree-sitter dump, not regex-based BSL tokenization."""
    ranges = {}
    lines = text.split("\n")
    for line in dump.splitlines():
        match = NODE.match(line)
        if not match:
            continue
        kind, first, start, last, end = match.groups()
        style = ("kw" if kind.endswith("_KEYWORD") else
                 {"string": "str", "date": "str", "number": "num",
                  "line_comment": "comment", "annotation": "annotation",
                  "preproc": "kw"}.get(kind))
        if style is None:
            continue
        first, start, last, end = map(int, (first, start, last, end))
        for number in range(first, last + 1):
            ranges.setdefault(number, []).append((start if number == first else 0,
                end if number == last else len(lines[number].encode()), style))
    return ranges


def styled_line(line, lexical, changed):
    data = line.encode()
    boundaries = {0, len(data)}
    for start, end, _ in lexical:
        boundaries.update((start, end))
    for start, end in changed:
        boundaries.update((start, end))
    pieces = []
    for start, end in itertools.pairwise(sorted(boundaries)):
        classes = [style for a, b, style in lexical if a <= start < b]
        if any(a <= start < b for a, b in changed):
            classes.append("changed")
        content = html.escape(data[start:end].decode())
        pieces.append(f'<span class="{" ".join(classes)}">{content}</span>' if classes else content)
    return "".join(pieces)


def preview(case, result, dumps):
    name, title, before, after, _ = case
    texts = {"lhs": before, "rhs": after}
    lexical = {side: syntax_ranges(dumps[side], text) for side, text in texts.items()}
    changes = {"lhs": {}, "rhs": {}}
    for chunk in result.get("chunks", []):
        for row in chunk:
            for side_name, side in row.items():
                changes[side_name].setdefault(side["line_number"], []).extend(
                    (c["start"], c["end"]) for c in side["changes"])
    for status, side in (("created", "rhs"), ("deleted", "lhs")):
        if result["status"] == status:
            changes[side] = {n: [(0, len(line.encode()))]
                             for n, line in enumerate(texts[side].split("\n"))}
    left, right = before.split("\n"), after.split("\n")
    alignment = result.get("aligned_lines")
    if not alignment:
        # No syntax changes/creation/deletion: JSON omits alignment. For this
        # preview only, show all actual lines; do not infer "no byte changes".
        alignment = list(itertools.zip_longest(range(len(left)), range(len(right))))
    rows = []
    for pair in alignment:
        cells = []
        for side, number in zip(("lhs", "rhs"), pair):
            lines = left if side == "lhs" else right
            body = "" if number is None else styled_line(lines[number],
                lexical[side].get(number, []), changes[side].get(number, []))
            cells.append(f'<td class="ln">{"" if number is None else number + 1}</td><td class="{side}"><pre>{body}</pre></td>')
        rows.append("<tr>" + "".join(cells) + "</tr>")
    raw = "".join(difflib.unified_diff(before.splitlines(keepends=True),
        after.splitlines(keepends=True), fromfile="Current", tofile="Proposed"))
    return (f'<section id="{name}"><h2>{html.escape(title)}</h2>'
        f'<p>{html.escape(result["language"])} · {result["status"]} · '
        f'байты различаются: {before.encode() != after.encode()}</p>'
        '<table><colgroup><col style="width:44px"><col><col style="width:44px"><col></colgroup>'
        '<thead><tr><th colspan="2">Текущий код</th><th colspan="2">Предложенный код</th></tr></thead><tbody>'
        + "".join(rows) + '</tbody></table><details><summary>Все текстовые изменения</summary><pre>'
        + html.escape(raw).replace(" ", "&#32;").replace("\r", "&#13;")
        + '</pre></details></section>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--difft", required=True, type=Path)
    parser.add_argument("--baseline", default="/usr/bin/difft", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--corpus", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert "BSL" in execute(args.difft, "--list-languages")
    assert "BSL" not in execute(args.baseline, "--list-languages")
    results, sections = [], []
    for case in CASES:
        name, title, before, after, expected = case
        folder = args.output / name
        folder.mkdir(exist_ok=True)
        paths = {"lhs": folder / "before.bsl", "rhs": folder / "after.bsl"}
        dumps = {}
        for side, text in (("lhs", before), ("rhs", after)):
            paths[side].write_bytes(text.encode())
            dumps[side] = execute(args.difft, "--dump-ts", paths[side])
            (folder / f"{side}.tree").write_text(dumps[side])
        result, elapsed = compare(args.difft, paths["lhs"], paths["rhs"])
        baseline, base_elapsed = compare(args.baseline, paths["lhs"], paths["rhs"])
        validate_ranges(result, {"lhs": before, "rhs": after})
        assert result["status"] == expected, (name, result)
        assert (result["language"] == "BSL") == (not name.startswith("invalid")), (name, result)
        if name == "expression":
            assert {c["content"] for c in tokens(result)} == {"100", "120"}
        (folder / "diff.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        (folder / "baseline.json").write_text(json.dumps(baseline, ensure_ascii=False, indent=2) + "\n")
        (folder / "diff.ansi").write_text(execute(args.difft, "--color=always",
            "--display=side-by-side-show-both", "--width=120", paths["lhs"], paths["rhs"]))
        results.append({"case": name, "title": title, "status": result["status"],
            "language": result["language"], "medianMs": elapsed,
            "baselineLanguage": baseline["language"], "baselineStatus": baseline["status"],
            "baselineMedianMs": base_elapsed, "byteChanges": before.encode() != after.encode(),
            "changedSpans": len(tokens(result)), "baselineChangedSpans": len(tokens(baseline)),
            "jsonSha256": hashlib.sha256((folder / "diff.json").read_bytes()).hexdigest()})
        sections.append(preview(case, result, dumps))
        print(f'{name}: {result["language"]}, {result["status"]}, {elapsed:.1f} ms')

    corpus = []
    if args.corpus:
        for path in sorted(args.corpus.rglob("*.bsl")):
            dump = execute(args.difft, "--dump-ts", path)
            errors = [line.strip() for line in dump.splitlines() if re.search(r"\b(?:ERROR|MISSING)\b", line)]
            # Adding whitespace forces a real parse even when a file is unchanged.
            # The JSON language also exposes MISSING nodes not labelled in dump-ts.
            probe = args.output / "corpus-probe.bsl"
            probe.write_bytes(path.read_bytes() + b"\n ")
            parsed = json.loads(execute(args.difft, "--display=json", path, probe))
            corpus.append({"file": str(path.relative_to(args.corpus)),
                           "language": parsed["language"], "treeDumpErrors": errors[:3]})

    large = "\n".join(module(f"    Результат = {n} + 1;\n    Возврат;", f"Метод{n}") for n in range(1000))
    large_before, large_after = args.output / "large-before.bsl", args.output / "large-after.bsl"
    large_before.write_text(large)
    large_after.write_text(large.replace("Результат = 500 + 1", "Результат = 500 + 2"))
    large_result, large_elapsed = compare(args.difft, large_before, large_after)
    validate_ranges(large_result, {"lhs": large, "rhs": large_after.read_text()})
    assert large_result["language"] == "BSL" and large_result["status"] == "changed"
    assert {c["content"] for c in tokens(large_result)} == {"1", "2"}
    guards = {}
    for option in ("--graph-limit=1", "--byte-limit=1"):
        result = json.loads(execute(args.difft, "--display=json", option,
            args.output / "expression/before.bsl", args.output / "expression/after.bsl"))
        assert result["language"].startswith("Text"), (option, result)
        assert result["status"] == "changed"
        guards[option] = result["language"]
    summary = {"binaryVersion": execute(args.difft, "--version").strip(),
        "binarySha256": hashlib.sha256(args.difft.read_bytes()).hexdigest(),
        "platform": platform.platform(),
        "baselineVersion": execute(args.baseline, "--version").strip(),
        "cases": results, "corpus": corpus, "fallbackGuards": guards,
        "largeModule": {"bytes": len(large.encode()), "lines": len(large.splitlines()),
                        "medianMs": large_elapsed, "changedSpans": len(tokens(large_result))}}
    (args.output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    nav = " · ".join(f'<a href="#{name}">{html.escape(title)}</a>' for name, title, *_ in CASES)
    document = '''<!doctype html><html lang="ru"><meta charset="utf-8">
<title>BSL + difftastic — эксперимент</title><style>
body{margin:24px;background:#131923;color:#dce4ef;font:15px system-ui}a{color:#85bbff}
nav{line-height:2}section{margin:36px 0}table{width:100%;border-collapse:collapse;table-layout:fixed}
th{background:#202c3e;padding:12px}td{vertical-align:top;border-bottom:1px solid #243044}
.ln{width:44px;color:#8393aa;text-align:right;padding-right:10px;user-select:none}
pre{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;font:13px/1.65 monospace;tab-size:4}
.lhs,.rhs{padding:0 8px}.kw{color:#c99cff;font-weight:bold}.str{color:#a9d58b}
.num{color:#ffc98b}.comment{color:#8496a9;font-style:italic}.annotation{color:#79cfd7}
.lhs .changed{background:#73333e;color:#fff}.rhs .changed{background:#235e44;color:#fff}
details{margin-top:12px;padding:12px;background:#1a2331}summary{cursor:pointer}
</style><h1>Структурный diff BSL</h1>
<p>Эксперимент: difftastic 0.71.0 + tree-sitter-bsl 0.1.7. Это статический просмотр, не окно EDT.</p>
<p>Подсветка кода взята из дерева tree-sitter; фон изменений — из JSON difftastic.
Строки — зелёные, ключевые слова — фиолетовые. Режим сравнения не определяет, какие байты применять.</p>
<p>Известные ограничения: нет отдельного статуса «перемещено»; запросы внутри строк не разбираются как SDBL;
JSON содержит только изменённые фрагменты; при ошибках разбора используется текстовый diff.</p><nav>'''
    (args.output / "preview.html").write_text(document + nav + "</nav>" + "".join(sections) + "</html>")
    print(f"PASS: {len(results)} scenarios; {len(corpus)} corpus modules; large module {large_elapsed:.1f} ms")


if __name__ == "__main__":
    main()
