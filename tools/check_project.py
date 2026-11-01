"""校验 project/ScratchMC.sb3 的结构和 opcode，避免拖进编辑器才发现积木是坏的。

    python tools/check_project.py
"""
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SB3 = os.path.join(ROOT, "project", "ScratchMC.sb3")
EXT = os.path.join(ROOT, "extension", "scratch-mc-inject.js")

KNOWN_SCRATCH = {
    "event_whenflagclicked", "event_whenkeypressed",
    "control_wait", "control_forever", "control_repeat", "control_if", "control_if_else",
    "control_create_clone_of", "control_start_as_clone",
    "data_setvariableto", "data_changevariableby",
    "data_addtolist", "data_deletealloflist", "data_lengthoflist", "data_itemoflist",
    "looks_say", "looks_sayforsecs", "looks_show", "looks_hide",
    "motion_gotoxy",
    "operator_add", "operator_subtract", "operator_multiply", "operator_join",
    "operator_equals", "operator_and",
    "sensing_mousedown", "sensing_touchingobject",
}


def points_to(up, bid):
    """上层块是不是真的引用了这个块（next / 任意 input）。"""
    if up.get("next") == bid:
        return True
    for value in up.get("inputs", {}).values():
        if isinstance(value, list) and len(value) >= 2 and isinstance(value[1], str) and value[1] == bid:
            return True
    return False


problems = []


def bad(msg):
    problems.append(msg)


def ext_opcodes():
    src = open(EXT, encoding="utf-8").read()
    names = re.findall(r"opcode:\s*'([A-Za-z0-9]+)'", src)
    return {"scratchMC_" + n for n in names}


def check_block_graph(target_name, blocks):
    for bid, node in blocks.items():
        opcode = node["opcode"]
        if opcode.startswith("scratchMC_"):
            if opcode not in EXT_OPS:
                bad("%s/%s: 扩展里没有这个 opcode %s" % (target_name, bid, opcode))
        elif opcode not in KNOWN_SCRATCH:
            bad("%s/%s: 未知 opcode %s" % (target_name, bid, opcode))

        if node.get("topLevel"):
            if node.get("parent") is not None:
                bad("%s/%s: topLevel 块却有 parent" % (target_name, bid))
        else:
            if not node.get("parent"):
                bad("%s/%s: 非 topLevel 块缺 parent" % (target_name, bid))

        nxt = node.get("next")
        if nxt:
            if nxt not in blocks:
                bad("%s/%s: next 指向不存在的块 %s" % (target_name, bid, nxt))
            elif blocks[nxt].get("parent") != bid:
                bad("%s/%s: next 的 parent 不对" % (target_name, bid))

        parent = node.get("parent")
        if parent and parent in blocks:
            if not points_to(blocks[parent], bid):
                bad("%s/%s: parent %s 既没 next 也没 input 指回来" % (target_name, bid, parent))

        for name, value in node.get("inputs", {}).items():
            if not isinstance(value, list) or len(value) < 2:
                continue
            target = value[1]
            if isinstance(target, str):
                if target not in blocks:
                    bad("%s/%s: 输入 %s 指向不存在的块 %s" % (target_name, bid, name, target))
                elif blocks[target].get("parent") != bid:
                    bad("%s/%s: 输入 %s 的 parent 不对" % (target_name, bid, name))

        for name, value in node.get("fields", {}).items():
            if not isinstance(value, list) or len(value) < 2:
                bad("%s/%s: field %s 格式不对" % (target_name, bid, name))


def check_primitives(project):
    """菜单 primitive 只能两段：[11, "值"]。

    写成 [11, "_myself_", null] 时，scratch-vm 的 sb3 schema 会直接拒掉整个工程
    （Could not parse as a valid SB2 or SB3 project），而 TurboWarp 只会把它报成
    扩展加载失败，极难排查。
    """
    for t in project["targets"]:
        for bid, node in t["blocks"].items():
            for name, value in node.get("inputs", {}).items():
                if not isinstance(value, list) or len(value) < 2:
                    continue
                inner = value[1]
                if isinstance(inner, list) and len(inner) >= 1 and inner[0] == 11 and len(inner) != 2:
                    bad("%s/%s: 输入 %s 的菜单 primitive 段数不对（%d 段，应为 2）：%r"
                        % (t["name"], bid, name, len(inner), inner))
                if isinstance(inner, list) and len(inner) >= 1 and inner[0] == 12 and len(inner) != 3:
                    bad("%s/%s: 输入 %s 的变量 primitive 段数不对：%r" % (t["name"], bid, name, inner))


def check_vars(project):
    stage = next(t for t in project["targets"] if t["isStage"])
    var_ids = set(stage["variables"]) | set(stage["lists"])
    for t in project["targets"]:
        if t["isStage"]:
            continue
        var_ids |= set(t["variables"])

    for t in project["targets"]:
        for bid, node in t["blocks"].items():
            for name, value in node.get("fields", {}).items():
                if name in ("VARIABLE", "LIST"):
                    if value[1] not in var_ids:
                        bad("%s/%s: field %s 引用了未知变量 id %s" % (t["name"], bid, name, value[1]))
            for name, value in node.get("inputs", {}).items():
                if len(value) >= 2 and isinstance(value[1], list) and len(value[1]) >= 3:
                    kind = value[1][0]
                    if kind == 12 and value[1][2] not in var_ids:
                        bad("%s/%s: 变量 %s 的 id 不存在" % (t["name"], bid, value[1][1]))
                    if kind == 13 and value[1][2] not in var_ids:
                        bad("%s/%s: 列表 %s 的 id 不存在" % (t["name"], bid, value[1][1]))


def check_monitors(project):
    stage = next(t for t in project["targets"] if t["isStage"])
    ids = set(stage["variables"]) | set(stage["lists"])
    for m in project["monitors"]:
        if m["id"] not in ids:
            bad("monitor %s 的 id 不在 Stage 变量/列表里" % m["id"])
    for name in ("模块", "开关"):
        if name not in [v[0] for v in stage["lists"].values()]:
            bad("Stage 缺列表 %s" % name)


def check_assets(project, z):
    names = set(z.namelist())
    for t in project["targets"]:
        if not t["costumes"]:
            bad("%s 没有造型" % t["name"])
        for c in t["costumes"]:
            if c["md5ext"] not in names:
                bad("%s 的造型文件 %s 不在包里" % (t["name"], c["md5ext"]))
                continue
            data = z.read(c["md5ext"])
            if hashlib.md5(data).hexdigest() != c["assetId"]:
                bad("%s 的造型 %s md5 不匹配" % (t["name"], c["name"]))
    layers = [t["layerOrder"] for t in project["targets"]]
    if len(layers) != len(set(layers)):
        bad("layerOrder 有重复：%s" % layers)


def main():
    if not os.path.exists(SB3):
        print("缺 %s，先跑 make_project.py" % SB3)
        return 1

    global EXT_OPS
    EXT_OPS = ext_opcodes()

    with zipfile.ZipFile(SB3) as z:
        project = json.loads(z.read("project.json").decode("utf-8"))
        check_assets(project, z)

    for t in project["targets"]:
        check_block_graph(t["name"], t["blocks"])
    check_primitives(project)
    check_vars(project)
    check_monitors(project)

    if "scratchMC" not in project.get("extensions", []):
        bad("extensions 里没有 scratchMC")
    if "scratchMC" not in project.get("extensionURLs", {}):
        bad("extensionURLs 里没有 scratchMC（打开工程不会自动加载扩展）")

    blocks = sum(len(t["blocks"]) for t in project["targets"])
    if problems:
        print("发现 %d 个问题：" % len(problems))
        for p in problems:
            print("  -", p)
        return 1

    print("OK  %d targets / %d blocks / %d monitors / %d 扩展 opcode"
          % (len(project["targets"]), blocks, len(project["monitors"]), len(EXT_OPS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
