"""生成 project/ScratchMC-inline.sb3 —— 把扩展以 data: URL 内联进工程。

用途：如果 TurboWarp 从 http://127.0.0.1:8791/extension.js 拉扩展时仍然报
"Error in sandboxed script"，用这个版本可以完全绕开「取脚本」这一步 ——
扩展字节已经躺在 sb3 里，TurboWarp 只需要 fetch 一个 data: URL。

    python tools/make_inline.py
"""
import base64
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "project", "ScratchMC.sb3")
EXT = os.path.join(ROOT, "extension", "scratch-mc-inject.js")
OUT = os.path.join(ROOT, "project", "ScratchMC-inline.sb3")


def main():
    js = open(EXT, "rb").read()
    data_url = "data:application/javascript;base64," + base64.b64encode(js).decode("ascii")

    with zipfile.ZipFile(SRC) as z:
        entries = [(info.filename, z.read(info.filename)) for info in z.infolist()]

    payloads = []
    for name, data in entries:
        if name == "project.json":
            project = json.loads(data.decode("utf-8"))
            project.setdefault("extensionURLs", {})["scratchMC"] = data_url
            data = json.dumps(project, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        payloads.append((name, data))

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in payloads:
            info = zipfile.ZipInfo(name, date_time=(2024, 1, 1, 0, 0, 0))
            z.writestr(info, data)

    copy = os.path.join(ROOT, "ScratchMC-inline.sb3")
    with open(OUT, "rb") as src, open(copy, "wb") as dst:
        dst.write(src.read())

    print("wrote %s (%d bytes, extension inlined as %d byte data URL)"
          % (OUT, os.path.getsize(OUT), len(data_url)))


if __name__ == "__main__":
    main()
