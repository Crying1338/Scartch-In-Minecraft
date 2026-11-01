/**
 * 离线校验 TurboWarp 扩展：不需要 Scratch、不需要 TurboWarp、不需要游戏。
 *
 *   node tools/check-extension.js
 *
 * 干三件事：
 *   1. 用假 Scratch 桩加载扩展，确认注册成功、getInfo() 能通过 structuredClone
 *      （sandbox 用 postMessage 回传，不可克隆就会变成 "Error in sandboxed script"）
 *   2. 确认 unsandboxed: true（否则桌面版 sandbox iframe 里 fetch 127.0.0.1 会被拦）
 *   3. 起一个假注入端，把 connect / 读状态 / 开关模块 / 改参数 全部走一遍
 */
'use strict';

const fs = require('fs');
const http = require('http');
const path = require('path');

const EXT = path.join(__dirname, '..', 'extension', 'scratch-mc-inject.js');
let fails = 0;

function check(name, ok, detail) {
  if (ok) {
    console.log('[PASS] ' + name + (detail ? '  (' + detail + ')' : ''));
  } else {
    fails++;
    console.log('[FAIL] ' + name + (detail ? '  (' + detail + ')' : ''));
  }
}

const status = {
  ok: true,
  name: 'ScratchMC',
  version: '1.0.0',
  pid: 4242,
  port: 8791,
  game: {
    ready: true,
    inGame: true,
    mcClass: 'net.minecraft.client.Minecraft',
    player: 'Steve',
    entities: 4,
    pos: [0.5, 64, 0.5],
    rot: [45, 12.5],
    canAim: true,
    canAttack: true,
    fail: '',
  },
  modules: { aim: true, trigger: false, esp: true },
  params: { range: 4, speed: 300, step: 8, fov: 30, sensStep: 0.15, target: 'mob', triggerMode: 'left' },
  target: { name: 'Zombie0', id: 200, dist: 1.8, dyaw: -3.5, dpitch: 1.2, hp: 20, locked: true },
  pulse: { ticks: 42, hz: 39.8, note: '', running: true },
};

const entities = {
  ok: true,
  count: 4,
  entities: [
    { id: 200, name: 'Zombie0', kind: 'minecraft:zombie', x: 1.5, y: 64, z: 2, dist: 1.8, hp: 20, dyaw: -3.5, dpitch: 1.2, living: true, player: false },
    { id: 201, name: 'Zombie1', kind: 'minecraft:zombie', x: 1.9, y: 64, z: 2.7, dist: 2.6, hp: 17, dyaw: -2.1, dpitch: 1.1, living: true, player: false },
    { id: 202, name: 'Zombie2', kind: 'minecraft:zombie', x: 2.3, y: 64, z: 3.4, dist: 3.4, hp: 14, dyaw: -1.4, dpitch: 1.0, living: true, player: false },
    { id: 300, name: 'Alex', kind: 'minecraft:player', x: -6, y: 64, z: 3, dist: 6.9, hp: 20, dyaw: 24, dpitch: 1.0, living: true, player: true },
  ],
};

function startStub() {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const send = (obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
        'Access-Control-Allow-Origin': '*',
      });
      res.end(body);
    };
    if (url.pathname === '/ping') return send({ ok: true, name: 'ScratchMC', version: '1.0.0', pid: 4242 });
    if (url.pathname === '/status') return send(status);
    if (url.pathname === '/entities') return send(entities);
    if (url.pathname === '/module') return send({ ok: true, note: '自瞄 开', modules: { aim: true, trigger: false, esp: true } });
    if (url.pathname === '/params') return send({ ok: true, note: 'FOV = 40°', params: status.params });
    if (url.pathname === '/look') return send({ ok: true, note: '已转向', rot: [90, 0] });
    if (url.pathname === '/attack') return send({ ok: true, note: '已攻击 Zombie0' });
    if (url.pathname === '/mappings') return send({ ok: true, report: 'Minecraft 类 = net.minecraft.client.Minecraft\nEntity 类 = net.minecraft.world.entity.Entity' });
    return send({ ok: false, error: 'no such endpoint ' + url.pathname });
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

(async () => {
  const src = fs.readFileSync(EXT, 'utf8');

  let instance = null;
  const stub = {
    extensions: {
      register(obj) {
        instance = obj;
      },
      unsandboxed: false,
    },
    BlockType: { COMMAND: 'command', REPORTER: 'reporter', BOOLEAN: 'Boolean', HAT: 'hat' },
    ArgumentType: { STRING: 'string', NUMBER: 'number', BOOLEAN: 'Boolean', COLOR: 'color' },
    TargetType: { SPRITE: 'sprite', STAGE: 'stage' },
    translate: (s) => s,
    canFetch: () => true,
    vm: {},
  };

  try {
    new Function('Scratch', 'fetch', 'XMLHttpRequest', 'console', src)(stub, globalThis.fetch, undefined, console);
  } catch (e) {
    check('扩展脚本能执行', false, e.message);
    process.exit(1);
  }
  check('扩展脚本能执行并注册', !!instance, instance ? 'ok' : 'Scratch.extensions.register 没被调用');
  if (!instance) {
    process.exit(1);
  }

  const info = instance.getInfo();
  check('getInfo().id = scratchMC', info.id === 'scratchMC', info.id);
  check('声明 unsandboxed（桌面版 sandbox 里 fetch 127.0.0.1 会被拦）', info.unsandboxed === true, String(info.unsandboxed));

  let cloned = null;
  try {
    cloned = structuredClone(info);
  } catch (e) {
    check('getInfo() 能被 structuredClone（sandbox postMessage 语义）', false, e.message);
  }
  check('getInfo() 能被 structuredClone（sandbox postMessage 语义）', !!cloned, cloned ? 'ok' : 'failed');

  const opcodes = new Set(info.blocks.filter((b) => typeof b === 'object' && b.opcode).map((b) => b.opcode));
  for (const name of ['connect', 'refresh', 'playerField', 'entityField', 'targetField', 'setModule', 'setParam', 'aimTo', 'attack', 'mappings', 'lastError', 'shutdown']) {
    check('积木 ' + name + ' 存在', opcodes.has(name), '');
  }
  const menuNames = new Set(Object.keys(info.menus || {}));
  for (const b of info.blocks) {
    if (typeof b !== 'object') continue;
    for (const arg of Object.values(b.arguments || {})) {
      if (arg.menu) {
        check('积木 ' + b.opcode + ' 的菜单 ' + arg.menu + ' 有定义', menuNames.has(arg.menu), '');
      }
    }
  }
  check('每个积木方法都实现了', info.blocks.every((b) => typeof b !== 'object' || typeof instance[b.opcode] === 'function'), '');

  const server = await startStub();
  const port = server.address().port;
  const base = 'http://127.0.0.1:' + port;

  try {
    const note = await instance.connect({ URL: base });
    check('connect 走通', String(note).includes('已连接'), note);

    check('playerField 名字 = Steve', (await instance.playerField({ FIELD: '名字' })) === 'Steve', '');
    check('playerField x = 0.5', Number(await instance.playerField({ FIELD: 'x' })) === 0.5, '');
    check('entityCount = 4', Number(await instance.entityCount()) === 4, '');
    check('entity 1 名字 = Zombie0', (await instance.entityField({ INDEX: 1, FIELD: '名字' })) === 'Zombie0', '');
    check('entity 2 距离 = 2.6', Math.abs(Number(await instance.entityField({ INDEX: 2, FIELD: '距离' })) - 2.6) < 0.01, '');
    check('targetField 名字', (await instance.targetField({ FIELD: '名字' })) === 'Zombie0', '');
    check('targetField 偏航差', Math.abs(Number(await instance.targetField({ FIELD: '偏航差' })) + 3.5) < 0.11, '');

    check('setModule 返回气泡', String(await instance.setModule({ NAME: 'aim', ON: '开' })).includes('自瞄'), '');
    check('setParam 返回气泡', String(await instance.setParam({ KEY: 'fov', VALUE: '40' })).includes('FOV'), '');
    check('refresh 返回气泡', String(await instance.refresh()).length > 0, '');
    check('aimTo 走通', String(await instance.aimTo({ YAW: 90, PITCH: 0 })).includes('转向'), '');
    check('attack 走通', String(await instance.attack()).includes('攻击'), '');
    check('mappings 有内容', String(await instance.mappings()).includes('Entity'), '');

    instance.base = 'http://127.0.0.1:1';
    const badNote = String(await instance.refresh());
    check('连不上时返回错误气泡', badNote.includes('错误'), badNote);
    check('连不上时记下错误', String(instance.lastError()).length > 0, instance.lastError());
  } finally {
    server.close();
  }

  console.log('=== ' + (fails === 0 ? '全部通过' : fails + ' 项失败') + ' ===');
  process.exit(fails === 0 ? 0 : 1);
})();
