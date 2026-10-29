#!/usr/bin/env python3
"""Open an attended structural-diff demo on a disposable Kasl copy; never accept it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path, help="Copied Kasl EDT project")
    parser.add_argument("--project", default="KASL")
    parser.add_argument("--port", default="8765")
    args = parser.parse_args()
    os.environ["MCP_PORT"] = args.port
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tests/e2e"))
    from harness import initialize, call, assert_ok

    module = "CommonModules/ОбщийКлиент/Module.bsl"
    target = args.directory.resolve() / "src" / module
    before = target.read_bytes()
    old = ("\tПолноеИмяФормы = Лев(Форма.ИмяФормы, Позиция) + ИмяФормы;\n"
           "\tОткрытьФорму(ПолноеИмяФормы, Параметры, Форма);")
    new = ("\tПолноеИмяФормы = Лев(\n\t\tФорма.ИмяФормы,\n\t\tПозиция + 0)\n"
           "\t\t+ ИмяФормы;\n\tОткрытьФорму(ПолноеИмяФормы, Параметры, Форма);")
    if before.decode("utf-8-sig").replace("\r\n", "\n").count(old) != 1:
        parser.error("Expected the original Kasl expression exactly once; use an unchanged disposable copy")
    initialize()
    result = call("write_module_source", {
        "projectName": args.project, "modulePath": module, "mode": "searchReplace",
        "oldSource": old, "source": new,
    })
    assert_ok(result, "open attended Kasl review")
    assert result.structured["status"] == "awaitingApproval", result.raw
    assert not result.structured["applied"], result.raw
    assert target.read_bytes() == before, "Source changed before human acceptance"
    print(json.dumps({"response": result.structured, "module": module,
        "baseSha256": hashlib.sha256(before).hexdigest(), "baseBytesUnchanged": True},
        ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
