/**
 * 交互回归：在 scratch-vm 里跑真扩展，验证「面板开关 -> 模块生效 -> 自瞄写视角 -> 攻击」
 * 这一整条链路。注入端要先在 127.0.0.1:8791 上跑着。
 *
 *   node tools/vmtest.js [project.sb3]
 *
 * 输出 [PASS]/[FAIL]，有失败就退出码 1。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SB3 = process.argv[2] || path.join(ROOT, 'ScratchMC.sb3');
const BASE = process.env.MCSI_BASE || 'http://127.0.0.1:8791';

let fails = 0;
function check(name, ok, detail) {
  if (ok) {
    console.log('[PASS] ' + name + (detail ? '  (' + detail + ')' : ''));
  } else {
    fails++;
    console.log('[FAIL] ' + name + (detail ? '  (' + detail + ')' : ''));
  }
}

// --- Scratch 桩 ---------------------------------------------------------
let instance = null;
const Scratch = {
  extensions: { register(o) { instance = o; }, unsandboxed: true },
  BlockType: { COMMAND: 'command', REPORTER: 'reporter', BOOLEAN: 'Boolean', HAT: 'hat' },
  ArgumentType: { STRING: 'string', NUMBER: 'number', BOOLEAN: 'Boolean' },
  TargetType: { SPRITE: 'sprite', STAGE: 'stage' },
  translate: (s) => s,
  vm: {},
};

async function status() {
  const res = await fetch(BASE + '/status');
  return res.json();
}

async function main() {
  const VM = require(require.resolve('scratch-vm', { paths: [path.join(__dirname, 'vmrun')] }));
  const vm = new VM();

  const extSrc = fs.readFileSync(path.join(ROOT, 'extension', 'scratch-mc-inject.js'), 'utf8');
  new Function('Scratch', 'fetch', 'XMLHttpRequest', 'console', extSrc)(
    Scratch, globalThis.fetch, globalThis.XMLHttpRequest, console
  );
  if (!instance) {
    throw new Error('扩展没注册');
  }
  vm.extensionManager._registerInternalExtension(instance);
  vm.extensionManager.loadExtensionURL = async () => instance;

  const buf = fs.readFileSync(SB3);
  await vm.loadProject(buf);
  check('sb3 通过 scratch-vm 的反序列化校验', true, path.basename(SB3) + ' ' + buf.length + 'B');

  const sprites = vm.runtime.targets.filter((t) => !t.isStage).map((t) => t.getName());
  check('三个角色都在', ['注入端', '模块面板', '准星'].every((n) => sprites.includes(n)), sprites.join(', '));

  vm.start();
  vm.greenFlag();
  await new Promise((r) => setTimeout(r, 3000));

  const stage = vm.runtime.getTargetForStage();
  const list = (name) => {
    const v = Object.values(stage.variables).find((x) => x.name === name);
    return v ? v.value : null;
  };
  check('模块列表已初始化', JSON.stringify(list('模块')) === JSON.stringify(['aim', 'trigger', 'esp']),
    JSON.stringify(list('模块')));
  check('开关列表已初始化', JSON.stringify(list('开关')) === JSON.stringify(['1', '1', '1']),
    JSON.stringify(list('开关')));

  const clones = vm.runtime.targets.filter((t) => !t.isOriginal);
  check('模块面板克隆出 3 个方块', clones.length === 3, 'clones=' + clones.length);
  const cloneModules = clones
    .map((c) => Object.values(c.variables).find((v) => v.name === '我的模块'))
    .map((v) => (v ? v.value : '?'))
    .sort();
  check('克隆体各拿到一个模块名', JSON.stringify(cloneModules) === JSON.stringify(['aim', 'esp', 'trigger']),
    cloneModules.join(','));

  // ---- 扩展侧：连注入端 ----
  const note = String(await instance.connect({ URL: BASE }));
  check('connect 到注入端', note.includes('已连接'), note);

  const st0 = await status();
  check('注入端认得玩家', !!st0.game.player, st0.game.player);
  const rot0 = st0.game.rot.slice();

  // ---- 参数：把自瞄调成一眼能看出效果的值 ----
  await instance.setParam({ KEY: 'range', VALUE: '16' });
  await instance.setParam({ KEY: 'fov', VALUE: '179' });
  await instance.setParam({ KEY: 'speed', VALUE: '900' });
  await instance.setParam({ KEY: 'step', VALUE: '30' });
  await instance.setParam({ KEY: 'sensStep', VALUE: '0' });

  const mod = String(await instance.setModule({ NAME: 'aim', ON: '开' }));
  check('模块 aim 开', mod.includes('自瞄'), mod);

  await new Promise((r) => setTimeout(r, 1200));
  const st1 = await status();
  check('注入端记录了 aim=true', st1.modules.aim === true, String(st1.modules.aim));
  check('自瞄把视角转过去了',
    Math.abs(st1.game.rot[0] - rot0[0]) > 0.5 || Math.abs(st1.game.rot[1] - rot0[1]) > 0.5,
    rot0.join('/') + ' -> ' + st1.game.rot.join('/'));
  check('锁定到目标', st1.target.locked === true, st1.target.name || 'none');
  check('Scratch 端能读到目标名',
    String(await instance.targetField({ FIELD: '名字' })).length > 0,
    String(await instance.targetField({ FIELD: '名字' })));

  const atk = String(await instance.attack());
  check('attack 走通', atk.includes('攻击'), atk);

  const ent = await instance.entityCount();
  check('实体数量可读', Number(ent) > 0, 'entities=' + ent);

  const maps = String(await instance.mappings());
  check('自检报告有内容', maps.includes('Minecraft'), maps.slice(0, 40));

  await instance.setModule({ NAME: 'aim', ON: '关' });
  const st2 = await status();
  check('模块 aim 关', st2.modules.aim === false, String(st2.modules.aim));

  vm.stopAll();
  console.log('=== ' + (fails === 0 ? '全部通过' : fails + ' 项失败') + ' ===');
  process.exit(fails === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error('FAILED:', e && e.stack ? e.stack : e);
  process.exit(1);
});
