/**
 * 无界面跑 sb3：scratch-vm + 我们的扩展，不需要 TurboWarp / 浏览器 / Scratch 编辑器。
 *
 *   node tools/vmrun.js [project.sb3] [refSeconds]
 *
 * 做的事：
 *   1. new VM()，把 extension/scratch-mc-inject.js 注册进去（和 TurboWarp 里同一份代码）
 *   2. loadProject(sb3) —— 积木图有问题这里就会抛
 *   3. greenFlag() 真的执行一遍：connect -> 报结果 -> 克隆模块面板 -> 循环刷新
 *   4. 把 VM 里每个角色的变量/列表，以及扩展的 lastErr 打出来
 *
 * 注入端（java(w).exe 里的 mcsi-agent.jar）要先在 8791 上跑着，
 * 否则 connect 会打印连接失败 —— 那也算跑通了，只是没有游戏。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SB3 = process.argv[2] || path.join(ROOT, 'ScratchMC.sb3');
const EXT = path.join(ROOT, 'extension', 'scratch-mc-inject.js');
const SECONDS = Number(process.argv[3] || 6);

function log(...args) {
  console.log(...args);
}

// --- 最小的 Scratch 全局桩，和 check-extension.js 同一套 -------------------
let instance = null;
const Scratch = {
  extensions: {
    register(obj) {
      instance = obj;
    },
    unsandboxed: true,
  },
  BlockType: { COMMAND: 'command', REPORTER: 'reporter', BOOLEAN: 'Boolean', HAT: 'hat' },
  ArgumentType: { STRING: 'string', NUMBER: 'number', BOOLEAN: 'Boolean', COLOR: 'color' },
  TargetType: { SPRITE: 'sprite', STAGE: 'stage' },
  translate: (s) => s,
  canFetch: () => true,
  vm: {},
};

function loadExtension() {
  const src = fs.readFileSync(EXT, 'utf8');
  new Function('Scratch', 'fetch', 'XMLHttpRequest', 'console', src)(
    Scratch,
    globalThis.fetch,
    globalThis.XMLHttpRequest,
    console
  );
  if (!instance) {
    throw new Error('扩展没有调用 Scratch.extensions.register');
  }
  return instance;
}

async function main() {
  const VM = require(require.resolve('scratch-vm', { paths: [path.join(__dirname, 'vmrun')] }));
  const vm = new VM();

  // 扩展必须在 loadProject 之前进 VM，否则块会被当成未知 opcode 丢掉
  const ext = loadExtension();
  vm.extensionManager._registerInternalExtension(ext);
  log('extension registered:', ext.getInfo().id);

  // VM 会因为工程里的 extensions/extensionURLs 去 loadExtensionURL，而 Node 里没有 Worker。
  // 我们已经在上面注册过了，这里直接短路返回同一个实例。
  const em = vm.extensionManager;
  const seen = [];
  em.loadExtensionURL = async (url) => {
    seen.push(String(url));
    return instance;
  };

  const buf = fs.readFileSync(SB3);
  await vm.loadProject(buf);
  log('extension URLs the project asked for:', seen.join(', ') || '(none)');
  log('project loaded:', path.basename(SB3), buf.length, 'bytes');

  const targets = vm.runtime.targets.filter((t) => !t.isStage);
  log('targets:', targets.map((t) => t.getName()).join(', '));
  const blockCount = targets.reduce((n, t) => n + Object.keys(t.blocks._blocks).length, 0);
  log('blocks in sprites:', blockCount, ' ext blocks:', ext.getInfo().blocks.length);

  vm.start();
  vm.greenFlag();
  log('green flag -> running for ' + SECONDS + 's ...');
  await new Promise((r) => setTimeout(r, SECONDS * 1000));
  vm.stopAll();

  // ---- 看结果 ----
  const stage = vm.runtime.getTargetForStage();
  log('\n--- stage variables ---');
  for (const [id, v] of Object.entries(stage.variables)) {
    if (v.type === 'list') {
      log('  list', v.name, '=', JSON.stringify(v.value));
    } else {
      log('  var ', v.name, '=', JSON.stringify(v.value));
    }
  }

  log('\n--- sprite variables ---');
  for (const t of targets) {
    for (const v of Object.values(t.variables)) {
      if (['我的序号', '我的模块', '我的开关'].includes(v.name)) {
        log('  ' + t.getName() + '.' + v.name, '=', JSON.stringify(v.value));
      }
    }
  }

  log('\n--- extension state ---');
  log('  connected :', ext.isConnected());
  log('  connect   :', ext.connectResult());
  log('  lastErr   :', ext.lastError());

  const clones = vm.runtime.targets.filter((t) => t.isOriginal === false);
  log('  clones    :', clones.length);
  for (const c of clones.slice(0, 4)) {
    const name = Object.values(c.variables).find((v) => v.name === '我的模块');
    log('    clone', c.getName(), 'module=', name ? name.value : '?');
  }

  process.exit(0);
}

main().catch((e) => {
  console.error('FAILED:', e && e.stack ? e.stack : e);
  process.exit(1);
});
