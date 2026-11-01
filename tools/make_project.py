"""生成 project/ScratchMC.sb3 —— 注入端配套的 Scratch 工程。

工程结构对应截图里的面板：
    Stage       列表「模块」「开关」+ 变量「序号 / 自瞄开关 / 锁定 / 距离」
    注入端      绿旗连接 + 自瞄状态刷新循环
    模块面板    按「模块」列表克隆出一排开关，点一下就把模块开/关打到注入端
    准星        把锁定目标的偏航差/俯仰差换算成屏幕坐标，当瞄准指示器

    python tools/make_project.py
"""
import hashlib
import json
import os
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "project", "ScratchMC.sb3")

BASE_URL = "http://127.0.0.1:8791"
MODULES = ["aim", "trigger", "esp"]

COSTUMES = {
    "背景": (
        '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="360">'
        '<rect width="480" height="360" fill="#20262e"/>'
        '<text x="24" y="44" font-family="monospace" font-size="22" fill="#6ee7a8">ScratchMC</text>'
        '<text x="24" y="72" font-family="monospace" font-size="13" fill="#8b98a8">'
        'inject -&gt; java(w).exe</text></svg>',
        240, 180),
    "猫": (
        '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">'
        '<rect x="6" y="16" width="52" height="40" rx="10" fill="#f4a261"/>'
        '<circle cx="24" cy="34" r="4" fill="#20262e"/><circle cx="42" cy="34" r="4" fill="#20262e"/>'
        '<path d="M22 46 q10 8 20 0" stroke="#20262e" stroke-width="3" fill="none"/></svg>',
        32, 32),
    "模块": (
        '<svg xmlns="http://www.w3.org/2000/svg" width="72" height="40">'
        '<rect x="1" y="1" width="70" height="38" rx="8" fill="#2f6f4f" '
        'stroke="#6ee7a8" stroke-width="2"/></svg>',
        36, 20),
    "准星": (
        '<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32">'
        '<circle cx="16" cy="16" r="12" fill="none" stroke="#ff5555" stroke-width="3"/>'
        '<circle cx="16" cy="16" r="2" fill="#ff5555"/></svg>',
        16, 16),
}


# --------------------------------------------------------------------- builder


class Builder:
    def __init__(self):
        self.blocks = {}
        self.n = 0

    def nid(self, prefix="b"):
        self.n += 1
        return "%s%d" % (prefix, self.n)

    def blk(self, opcode, inputs=None, fields=None, parent=None, top=False, x=0, y=0):
        bid = self.nid()
        node = {
            "opcode": opcode,
            "next": None,
            "parent": parent,
            "inputs": inputs or {},
            "fields": fields or {},
            "shadow": False,
            "topLevel": bool(top),
        }
        if top:
            node["x"] = x
            node["y"] = y
        self.blocks[bid] = node
        return bid

    def stack(self, steps, parent=None):
        first = None
        prev = None
        for opcode, inputs, fields in steps:
            bid = self.blk(opcode, inputs, fields, parent=prev)
            if prev:
                self.blocks[prev]["next"] = bid
            else:
                first = bid
            prev = bid
        if first:
            self.blocks[first]["parent"] = parent
        return first, prev

    def append(self, tail, steps):
        first, last = self.stack(steps)
        if tail:
            self.blocks[tail]["next"] = first
        return first, last

    def block_with_sub(self, opcode, inputs, sub_first, parent=None):
        data = dict(inputs)
        data["SUBSTACK"] = [2, sub_first] if sub_first else None
        return self.blk(opcode, data, parent=parent)

    def block_with_two_subs(self, opcode, inputs, first, second, parent=None):
        data = dict(inputs)
        data["SUBSTACK"] = [2, first] if first else None
        data["SUBSTACK2"] = [2, second] if second else None
        return self.blk(opcode, data, parent=parent)


def num(v):
    return [1, [4, str(v)]]


def text(v):
    return [1, [10, str(v)]]


def var(name, vid):
    return [3, [12, name, vid], [4, "0"]]


def ref(block_id):
    return [3, block_id, [10, ""]]


def ref_menu(block_id, fallback):
    """acceptReporters 菜单接一个 reporter 时，第三段放菜单默认值当 shadow。"""
    return [3, block_id, [11, fallback]]


def menu(value):
    return [1, [11, str(value)]]


def var_menu(name, vid, fallback):
    """把变量接到 acceptReporters 菜单上：第三段放菜单默认值当 shadow。"""
    return [3, [12, name, vid], [11, fallback]]


def cond(block_id):
    return [2, block_id]


def fix_parents(blocks):
    for bid, node in blocks.items():
        for value in node["inputs"].values():
            if not isinstance(value, list) or len(value) < 2:
                continue
            target = value[1]
            if isinstance(target, str) and target in blocks:
                blocks[target]["parent"] = bid
        nxt = node.get("next")
        if isinstance(nxt, str) and nxt in blocks:
            blocks[nxt]["parent"] = bid
    return blocks


# ---------------------------------------------------------------------- 角色 1


def driver_blocks():
    """绿旗：连接 -> 报连接结果 -> 报自检 -> 循环刷新。"""
    b = Builder()
    head = b.blk("event_whenflagclicked", top=True, x=40, y=40)

    connect = b.blk("scratchMC_connect", {"URL": text(BASE_URL)}, parent=head)
    b.blocks[head]["next"] = connect

    note_connect = b.blk("scratchMC_connectResult")
    say1 = b.blk("looks_sayforsecs", {"MESSAGE": ref(note_connect), "SECS": num(2)}, parent=connect)
    b.blocks[connect]["next"] = say1

    note_map = b.blk("scratchMC_mappings")
    say2 = b.blk("looks_sayforsecs", {"MESSAGE": ref(note_map), "SECS": num(4)}, parent=say1)
    b.blocks[say1]["next"] = say2

    body_first, _ = b.stack([
        ("scratchMC_refresh", {}, {}),
        ("control_wait", {"DURATION": num(0.2)}, {}),
    ])
    forever = b.block_with_sub("control_forever", {}, body_first, parent=say2)
    b.blocks[say2]["next"] = forever
    return fix_parents(b.blocks)


# ---------------------------------------------------------------------- 角色 2


def panel_blocks():
    """模块面板：按列表克隆开关，点一下就开/关。"""
    b = Builder()
    head = b.blk("event_whenflagclicked", top=True, x=40, y=40)

    steps = [
        ("data_setvariableto", {"VALUE": num(0)}, {"VARIABLE": ["序号", "var_seq"]}),
        ("data_deletealloflist", {}, {"LIST": ["模块", "list_modules"]}),
        ("data_deletealloflist", {}, {"LIST": ["开关", "list_switch"]}),
    ]
    for m in MODULES:
        steps.append(("data_addtolist", {"ITEM": text(m)}, {"LIST": ["模块", "list_modules"]}))
        steps.append(("data_addtolist", {"ITEM": num(1)}, {"LIST": ["开关", "list_switch"]}))
    steps.append(("data_setvariableto", {"VALUE": num(1)}, {"VARIABLE": ["序号", "var_seq"]}))

    init_first, init_last = b.stack(steps, parent=head)
    b.blocks[head]["next"] = init_first

    # repeat (模块 的项目数) { 复制给克隆体 -> 造克隆体 -> 序号 + 1 }
    item_of_list = b.blk("data_itemoflist",
                         {"INDEX": var("序号", "var_seq")},
                         {"LIST": ["模块", "list_modules"]})
    clone_body, _ = b.stack([
        ("data_setvariableto", {"VALUE": var("序号", "var_seq")}, {"VARIABLE": ["我的序号", "my_index"]}),
        ("data_setvariableto", {"VALUE": ref(item_of_list)}, {"VARIABLE": ["我的模块", "my_module"]}),
        ("data_setvariableto", {"VALUE": num(1)}, {"VARIABLE": ["我的开关", "my_switch"]}),
        ("control_create_clone_of", {"CLONE_OPTION": menu("_myself_")}, {}),
        ("data_changevariableby", {"VALUE": num(1)}, {"VARIABLE": ["序号", "var_seq"]}),
    ])
    length_id = b.blk("data_lengthoflist", {}, {"LIST": ["模块", "list_modules"]})
    repeat = b.block_with_sub("control_repeat", {"TIMES": ref(length_id)}, clone_body, parent=init_last)
    b.blocks[init_last]["next"] = repeat

    hide = b.blk("looks_hide", parent=repeat)
    b.blocks[repeat]["next"] = hide

    # ---- 克隆体 ----
    clone_head = b.blk("control_start_as_clone", top=True, x=560, y=40)

    sub1 = b.blk("operator_subtract", {"NUM1": var("我的序号", "my_index"), "NUM2": num(1)})
    mul = b.blk("operator_multiply", {"NUM1": ref(sub1), "NUM2": num(105)})
    addx = b.blk("operator_add", {"NUM1": num(-170), "NUM2": ref(mul)})

    setup_first, setup_last = b.stack([
        ("motion_gotoxy", {"X": ref(addx), "Y": num(120)}, {}),
        ("looks_show", {}, {}),
        ("looks_say", {"MESSAGE": var("我的模块", "my_module")}, {}),
    ], parent=clone_head)
    b.blocks[clone_head]["next"] = setup_first

    # 点击切换
    mousedown = b.blk("sensing_mousedown")
    touching = b.blk("sensing_touchingobject", {"TOUCHINGOBJECTMENU": menu("_mouse_")})
    both = b.blk("operator_and", {"OPERAND1": cond(mousedown), "OPERAND2": cond(touching)})

    flip = b.blk("operator_subtract", {"NUM1": num(1), "NUM2": var("我的开关", "my_switch")})
    is_on = b.blk("operator_equals", {"OPERAND1": var("我的开关", "my_switch"), "OPERAND2": num(1)})

    say_join = b.blk("operator_join", {
        "STRING1": var("我的模块", "my_module"),
        "STRING2": ref(b.blk("operator_join", {
            "STRING1": text(" -> "),
            "STRING2": var("我的开关", "my_switch"),
        })),
    })

    on_steps, _ = b.stack([
        ("scratchMC_setModule",
         {"NAME": var_menu("我的模块", "my_module", "aim")},
         {"ON": ["开", None]}),
    ])
    off_steps, _ = b.stack([
        ("scratchMC_setModule",
         {"NAME": var_menu("我的模块", "my_module", "aim")},
         {"ON": ["关", None]}),
    ])
    branch = b.block_with_two_subs("control_if_else", {"CONDITION": cond(is_on)}, on_steps, off_steps)

    body_first, body_last = b.stack([
        ("data_setvariableto", {"VALUE": ref(flip)}, {"VARIABLE": ["我的开关", "my_switch"]}),
        ("looks_say", {"MESSAGE": ref(say_join)}, {}),
    ])
    b.blocks[body_last]["next"] = branch

    guard = b.block_with_sub("control_if", {"CONDITION": cond(both)}, body_first)
    forever = b.block_with_sub("control_forever", {}, guard, parent=setup_last)
    b.blocks[setup_last]["next"] = forever

    return fix_parents(b.blocks)


# ---------------------------------------------------------------------- 角色 3


def crosshair_blocks():
    """准星：把锁定目标的偏航差/俯仰差映射成屏幕坐标；按 1 切自瞄。"""
    b = Builder()
    head = b.blk("event_whenflagclicked", top=True, x=40, y=40)

    aim_on = b.blk("operator_equals", {
        "OPERAND1": var("自瞄开关", "var_aim"),
        "OPERAND2": num(1),
    })
    t_name = b.blk("scratchMC_targetField", {"FIELD": menu("名字")})
    t_dist = b.blk("scratchMC_targetField", {"FIELD": menu("距离")})
    t_yaw = b.blk("scratchMC_targetField", {"FIELD": menu("偏航差")})
    t_pitch = b.blk("scratchMC_targetField", {"FIELD": menu("俯仰差")})

    px = b.blk("operator_multiply", {"NUM1": ref(t_yaw), "NUM2": num(5)})
    py = b.blk("operator_multiply", {"NUM1": ref(t_pitch), "NUM2": num(-5)})

    body_first, _ = b.stack([
        ("data_setvariableto", {"VALUE": ref(t_name)}, {"VARIABLE": ["锁定", "var_lock"]}),
        ("data_setvariableto", {"VALUE": ref(t_dist)}, {"VARIABLE": ["距离", "var_dist"]}),
        ("motion_gotoxy", {"X": ref(px), "Y": ref(py)}, {}),
        ("control_wait", {"DURATION": num(0.05)}, {}),
    ])
    guard = b.block_with_sub("control_if", {"CONDITION": cond(aim_on)}, body_first)
    forever = b.block_with_sub("control_forever", {}, guard)

    show = b.blk("looks_show", parent=head)
    b.blocks[head]["next"] = show
    b.blocks[show]["next"] = forever

    # 按 1 切换自瞄
    key_head = b.blk("event_whenkeypressed", fields={"KEY_OPTION": ["1", None]}, top=True, x=560, y=40)
    off_state = b.blk("operator_equals", {"OPERAND1": var("自瞄开关", "var_aim"), "OPERAND2": num(0)})

    on_steps, _ = b.stack([
        ("data_setvariableto", {"VALUE": num(1)}, {"VARIABLE": ["自瞄开关", "var_aim"]}),
        ("scratchMC_setModule", {"NAME": menu("aim")}, {"ON": ["开", None]}),
        ("looks_sayforsecs", {"MESSAGE": text("自瞄 开"), "SECS": num(1)}, {}),
    ])
    off_steps, _ = b.stack([
        ("data_setvariableto", {"VALUE": num(0)}, {"VARIABLE": ["自瞄开关", "var_aim"]}),
        ("scratchMC_setModule", {"NAME": menu("aim")}, {"ON": ["关", None]}),
        ("looks_sayforsecs", {"MESSAGE": text("自瞄 关"), "SECS": num(1)}, {}),
    ])
    toggle = b.block_with_two_subs("control_if_else", {"CONDITION": cond(off_state)}, on_steps, off_steps,
                                   parent=key_head)
    b.blocks[key_head]["next"] = toggle
    return fix_parents(b.blocks)


# ----------------------------------------------------------------------- 工程


def costume_entry(name):
    svg = COSTUMES[name][0].encode("utf-8")
    digest = hashlib.md5(svg).hexdigest()
    return {
        "name": name,
        "bitmapResolution": 1,
        "dataFormat": "svg",
        "assetId": digest,
        "md5ext": digest + ".svg",
        "rotationCenterX": COSTUMES[name][1],
        "rotationCenterY": COSTUMES[name][2],
    }


def stage_target():
    variables = {
        "var_seq": ["序号", 0],
        "var_url": ["连接地址", BASE_URL],
        "var_aim": ["自瞄开关", 0],
        "var_lock": ["锁定", ""],
        "var_dist": ["距离", 0],
    }
    lists = {
        "list_modules": ["模块", list(MODULES)],
        "list_switch": ["开关", [1, 1, 1]],
    }
    return {
        "isStage": True,
        "name": "Stage",
        "variables": variables,
        "lists": lists,
        "broadcasts": {},
        "blocks": {},
        "comments": {},
        "currentCostume": 0,
        "costumes": [costume_entry("背景")],
        "sounds": [],
        "volume": 100,
        "layerOrder": 0,
        "tempo": 60,
        "videoTransparency": 50,
        "videoState": "on",
        "textToSpeechLanguage": None,
    }


def sprite(name, blocks, layer, costume, x=0, y=0, variables=None):
    return {
        "isStage": False,
        "name": name,
        "variables": variables or {},
        "lists": {},
        "broadcasts": {},
        "blocks": blocks,
        "comments": {},
        "currentCostume": 0,
        "costumes": [costume_entry(costume)],
        "sounds": [],
        "volume": 100,
        "layerOrder": layer,
        "visible": True,
        "x": x,
        "y": y,
        "size": 100,
        "direction": 90,
        "draggable": False,
        "rotationStyle": "all around",
    }


def monitors():
    return [
        {
            "id": "list_modules", "mode": "list", "opcode": "data_listcontents",
            "params": {"LIST": "模块"}, "spriteName": None, "value": list(MODULES),
            "width": 110, "height": 132, "x": 6, "y": 6, "visible": True,
        },
        {
            "id": "list_switch", "mode": "list", "opcode": "data_listcontents",
            "params": {"LIST": "开关"}, "spriteName": None, "value": [1, 1, 1],
            "width": 80, "height": 132, "x": 124, "y": 6, "visible": True,
        },
        {
            "id": "var_lock", "mode": "default", "opcode": "data_variable",
            "params": {"VARIABLE": "锁定"}, "spriteName": None, "value": "",
            "width": 0, "height": 0, "x": 212, "y": 6, "visible": True,
            "sliderMin": 0, "sliderMax": 100, "isDiscrete": True,
        },
        {
            "id": "var_dist", "mode": "default", "opcode": "data_variable",
            "params": {"VARIABLE": "距离"}, "spriteName": None, "value": 0,
            "width": 0, "height": 0, "x": 212, "y": 38, "visible": True,
            "sliderMin": 0, "sliderMax": 100, "isDiscrete": True,
        },
    ]


def build():
    return {
        "targets": [
            stage_target(),
            sprite("注入端", driver_blocks(), 1, "猫", x=-140, y=-120),
            sprite("模块面板", panel_blocks(), 2, "模块", x=0, y=120, variables={
                "my_index": ["我的序号", 0],
                "my_module": ["我的模块", ""],
                "my_switch": ["我的开关", 1],
            }),
            sprite("准星", crosshair_blocks(), 3, "准星", x=0, y=0),
        ],
        "monitors": monitors(),
        "extensions": ["scratchMC"],
        "extensionURLs": {"scratchMC": BASE_URL + "/extension.js"},
        "meta": {"semver": "3.0.0", "vm": "0.2.0", "agent": "ScratchMC make_project.py"},
    }


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    project = build()
    payload = json.dumps(project, ensure_ascii=False, separators=(",", ":"))

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        info = zipfile.ZipInfo("project.json", date_time=(2024, 1, 1, 0, 0, 0))
        z.writestr(info, payload)
        seen = set()
        for name in COSTUMES:
            entry = costume_entry(name)
            if entry["md5ext"] in seen:
                continue
            seen.add(entry["md5ext"])
            info = zipfile.ZipInfo(entry["md5ext"], date_time=(2024, 1, 1, 0, 0, 0))
            z.writestr(info, COSTUMES[name][0].encode("utf-8"))

    blocks = sum(len(t["blocks"]) for t in project["targets"])
    print("wrote %s (%d bytes, %d blocks, %d targets)"
          % (OUT, os.path.getsize(OUT), blocks, len(project["targets"])))


if __name__ == "__main__":
    main()
