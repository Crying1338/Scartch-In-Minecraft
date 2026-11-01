/**
 * ScratchMC 注入端 —— TurboWarp / PenguinMod 扩展。
 *
 * 积木本身只是 HTTP 薄壳：真正读游戏内存、写视角、打攻击的是注入进
 * java(w).exe 的 mcsi-agent.jar（java/src/mcsi/*.java）。
 *
 * 加载方式：
 *   扩展 -> 加载扩展 -> 从 URL 加载 -> http://127.0.0.1:8791/extension.js
 * 或者直接把 project/ScratchMC.sb3 拖进编辑器（工程里带了 extensionURLs）。
 *
 * 所有请求都在 127.0.0.1 上，不碰外网。
 */
(function (Scratch) {
  'use strict';

  const DEFAULT_BASE = 'http://127.0.0.1:8791';
  const STATUS_CACHE_MS = 100;
  const ENTITY_CACHE_MS = 180;

  class ScratchMc {
    constructor() {
      this.base = DEFAULT_BASE;
      this.token = '';
      this.connected = false;
      this.lastErr = '';
      this.connectNote = '';
      this.stateCache = { at: 0, data: null };
      this.entityCache = { at: 0, list: [], count: 0 };
    }

    getInfo() {
      return {
        id: 'scratchMC',
        name: 'ScratchMC 注入端',
        // TurboWarp 桌面版默认把扩展放进 sandbox iframe，那里 fetch http://127.0.0.1
        // 会被 CSP / 本地网络访问策略拦掉（表现为 "Error in sandboxed script"）。
        unsandboxed: true,
        color1: '#2f6f4f',
        color2: '#245a40',
        color3: '#1a4530',
        blocks: [
          {
            opcode: 'connect',
            blockType: Scratch.BlockType.COMMAND,
            text: '连接注入端 [URL]',
            arguments: {
              URL: { type: Scratch.ArgumentType.STRING, defaultValue: DEFAULT_BASE },
            },
          },
          {
            opcode: 'isConnected',
            blockType: Scratch.BlockType.BOOLEAN,
            text: '注入端已连接？',
          },
          {
            opcode: 'connectResult',
            blockType: Scratch.BlockType.REPORTER,
            text: '连接结果',
          },
          '---',
          {
            opcode: 'refresh',
            blockType: Scratch.BlockType.COMMAND,
            text: '刷新状态',
          },
          {
            opcode: 'playerField',
            blockType: Scratch.BlockType.REPORTER,
            text: '玩家 [FIELD]',
            arguments: {
              FIELD: { type: Scratch.ArgumentType.STRING, menu: 'playerField', defaultValue: 'x' },
            },
          },
          {
            opcode: 'entityCount',
            blockType: Scratch.BlockType.REPORTER,
            text: '实体数量',
          },
          {
            opcode: 'entityField',
            blockType: Scratch.BlockType.REPORTER,
            text: '实体 [INDEX] 的 [FIELD]',
            arguments: {
              INDEX: { type: Scratch.ArgumentType.NUMBER, defaultValue: 1 },
              FIELD: { type: Scratch.ArgumentType.STRING, menu: 'entityField', defaultValue: '名字' },
            },
          },
          {
            opcode: 'targetField',
            blockType: Scratch.BlockType.REPORTER,
            text: '锁定目标 [FIELD]',
            arguments: {
              FIELD: { type: Scratch.ArgumentType.STRING, menu: 'entityField', defaultValue: '名字' },
            },
          },
          '---',
          {
            opcode: 'setModule',
            blockType: Scratch.BlockType.COMMAND,
            text: '模块 [NAME] [ON]',
            arguments: {
              NAME: { type: Scratch.ArgumentType.STRING, menu: 'module', defaultValue: 'aim' },
              ON: { type: Scratch.ArgumentType.STRING, menu: 'onoff', defaultValue: '开' },
            },
          },
          {
            opcode: 'setParam',
            blockType: Scratch.BlockType.COMMAND,
            text: '设置 [KEY] 为 [VALUE]',
            arguments: {
              KEY: { type: Scratch.ArgumentType.STRING, menu: 'paramKey', defaultValue: 'fov' },
              VALUE: { type: Scratch.ArgumentType.STRING, defaultValue: '30' },
            },
          },
          {
            opcode: 'aimTo',
            blockType: Scratch.BlockType.COMMAND,
            text: '转向 偏航 [YAW] 俯仰 [PITCH]',
            arguments: {
              YAW: { type: Scratch.ArgumentType.NUMBER, defaultValue: 0 },
              PITCH: { type: Scratch.ArgumentType.NUMBER, defaultValue: 0 },
            },
          },
          {
            opcode: 'attack',
            blockType: Scratch.BlockType.COMMAND,
            text: '攻击锁定目标',
          },
          '---',
          {
            opcode: 'mappings',
            blockType: Scratch.BlockType.REPORTER,
            text: '自检报告',
          },
          {
            opcode: 'pulseNote',
            blockType: Scratch.BlockType.REPORTER,
            text: '脉冲提示',
          },
          {
            opcode: 'lastError',
            blockType: Scratch.BlockType.REPORTER,
            text: '最后一次错误',
          },
          {
            opcode: 'shutdown',
            blockType: Scratch.BlockType.COMMAND,
            text: '停止注入端',
          },
        ],
        menus: {
          playerField: {
            acceptReporters: true,
            items: ['x', 'y', 'z', '偏航', '俯仰', '血量', '名字'],
          },
          entityField: {
            acceptReporters: true,
            items: ['名字', '距离', '偏航差', '俯仰差', '血量', '类型', 'x', 'y', 'z'],
          },
          module: {
            acceptReporters: true,
            items: ['aim', 'trigger', 'esp'],
          },
          onoff: {
            acceptReporters: false,
            items: ['开', '关'],
          },
          paramKey: {
            acceptReporters: true,
            items: ['range', 'speed', 'step', 'fov', 'sensStep', '目标', '触发方式', '排序', '平滑'],
          },
        },
      };
    }

    // ------------------------------------------------------------- plumbing

    normalizeBase(url) {
      let base = String(url || DEFAULT_BASE).trim();
      if (!/^https?:\/\//i.test(base)) {
        base = 'http://' + base;
      }
      return base.replace(/\/+$/, '');
    }

    async request(path, body) {
      const reply = await this.raw(path, body);
      let data = {};
      try {
        data = reply.text ? JSON.parse(reply.text) : {};
      } catch (e) {
        data = { ok: false, error: '注入端返回了非 JSON：' + String(reply.text).slice(0, 80) };
      }
      if (!reply.ok || data.ok === false) {
        this.connected = false;
        this.lastErr = data.error || 'HTTP ' + reply.status;
        throw new Error(this.lastErr);
      }
      this.connected = true;
      this.lastErr = '';
      return data;
    }

    /** fetch 优先；被策略拦时退回 XHR（桌面版两种都试过再报错）。 */
    async raw(path, body) {
      const url = this.base + path;
      const payload = body === undefined ? undefined : JSON.stringify(body);
      const headers = {};
      if (this.token) {
        headers['X-MC-Token'] = this.token;
      }
      if (payload !== undefined) {
        headers['Content-Type'] = 'application/json';
      }
      try {
        const res = await fetch(url, {
          method: payload === undefined ? 'GET' : 'POST',
          headers,
          body: payload,
          cache: 'no-store',
        });
        return { ok: res.ok, status: res.status, text: await res.text() };
      } catch (fetchError) {
        return this.xhr(url, payload === undefined ? 'GET' : 'POST', payload, headers, fetchError);
      }
    }

    xhr(url, method, payload, headers, fetchError) {
      return new Promise((resolve, reject) => {
        let req;
        try {
          req = new XMLHttpRequest();
          req.open(method, url, true);
          Object.keys(headers).forEach((k) => req.setRequestHeader(k, headers[k]));
        } catch (e) {
          // 这里把 fetch 的原始失败原因也带上，否则只剩 "XMLHttpRequest is not a constructor"
          reject(
            new Error(
              '连不上注入端（fetch: ' +
                (fetchError && fetchError.message ? fetchError.message : '?') +
                ' / xhr: ' +
                e.message +
                '）'
            )
          );
          return;
        }
        req.onload = () =>
          resolve({ ok: req.status >= 200 && req.status < 300, status: req.status, text: req.responseText });
        req.onerror = () =>
          reject(
            new Error(
              '连不上注入端（fetch 与 XHR 都失败）：' +
                (fetchError && fetchError.message ? fetchError.message : 'network')
            )
          );
        try {
          req.send(payload === undefined ? null : payload);
        } catch (e) {
          reject(new Error('连不上注入端：' + e.message));
        }
      });
    }

    async status(force) {
      const now = Date.now();
      if (!force && this.stateCache.data && now - this.stateCache.at < STATUS_CACHE_MS) {
        return this.stateCache.data;
      }
      const data = await this.request('/status');
      this.stateCache = { at: now, data };
      return data;
    }

    async entities() {
      const now = Date.now();
      if (now - this.entityCache.at < ENTITY_CACHE_MS) {
        return this.entityCache;
      }
      const data = await this.request('/entities?range=32&filter=all&limit=32');
      this.entityCache = { at: now, list: data.entities || [], count: data.count || 0 };
      return this.entityCache;
    }

    safe(fn, fallback) {
      try {
        return fn();
      } catch (e) {
        this.lastErr = e && e.message ? e.message : String(e);
        return fallback;
      }
    }

    // --------------------------------------------------------------- blocks

    async connect(args) {
      this.base = this.normalizeBase(args.URL);
      try {
        const ping = await this.request('/ping');
        const status = await this.status(true);
        this.connectNote =
          '已连接 ' +
          this.base +
          '（pid ' +
          ping.pid +
          '，' +
          (status.game && status.game.player ? '玩家 ' + status.game.player : '还没进世界') +
          '）';
        return this.connectNote;
      } catch (e) {
        this.connectNote = '连接失败：' + (e.message || e) + '（先注入 mcsi-agent.jar）';
        return this.connectNote;
      }
    }

    isConnected() {
      return this.connected;
    }

    connectResult() {
      return this.connectNote || this.lastErr;
    }

    async refresh() {
      return this.safeCmd(async () => {
        const status = await this.status(true);
        await this.entities();
        const pulse = status.pulse || {};
        if (pulse.note) {
          return pulse.note;
        }
        return status.game.player ? '已刷新：' + status.game.player : '已刷新（还没进世界）';
      });
    }

    async playerField(args) {
      return this.safeAsync(async () => {
        const status = await this.status(false);
        const game = status.game || {};
        const pos = game.pos || [0, 0, 0];
        const rot = game.rot || [0, 0];
        switch (String(args.FIELD)) {
          case 'x':
            return pos[0];
          case 'y':
            return pos[1];
          case 'z':
            return pos[2];
          case '偏航':
            return rot[0];
          case '俯仰':
            return rot[1];
          case '血量':
            return 20;
          case '名字':
            return game.player || '';
          default:
            return '';
        }
      }, '');
    }

    async entityCount() {
      return this.safeAsync(async () => {
        const e = await this.entities();
        return e.count;
      }, 0);
    }

    async entityField(args) {
      return this.safeAsync(async () => {
        const list = (await this.entities()).list;
        let index = Math.round(Number(args.INDEX) || 1);
        if (index < 1) {
          index = 1;
        }
        const item = list[index - 1];
        if (!item) {
          return '';
        }
        return this.pick(item, String(args.FIELD));
      }, '');
    }

    async targetField(args) {
      return this.safeAsync(async () => {
        const status = await this.status(false);
        const target = status.target || {};
        if (!target.locked) {
          return '';
        }
        return this.pick(
          {
            name: target.name,
            dist: target.dist,
            dyaw: target.dyaw,
            dpitch: target.dpitch,
            hp: target.hp,
            kind: '',
            x: '',
            y: '',
            z: '',
          },
          String(args.FIELD)
        );
      }, '');
    }

    pick(item, field) {
      switch (field) {
        case '名字':
          return item.name || '';
        case '距离':
          return item.dist === undefined ? '' : Math.round(item.dist * 100) / 100;
        case '偏航差':
          return item.dyaw === undefined ? '' : Math.round(item.dyaw * 10) / 10;
        case '俯仰差':
          return item.dpitch === undefined ? '' : Math.round(item.dpitch * 10) / 10;
        case '血量':
          return item.hp === undefined ? '' : item.hp;
        case '类型':
          return item.kind || '';
        case 'x':
          return item.x === undefined ? '' : Math.round(item.x * 100) / 100;
        case 'y':
          return item.y === undefined ? '' : Math.round(item.y * 100) / 100;
        case 'z':
          return item.z === undefined ? '' : Math.round(item.z * 100) / 100;
        default:
          return '';
      }
    }

    async setModule(args) {
      const on = String(args.ON) === '开' || String(args.ON) === '1' || String(args.ON) === 'true';
      return this.safeCmd(async () => {
        const data = await this.request('/module', { name: String(args.NAME), on });
        return data.note || '完成';
      });
    }

    async setParam(args) {
      return this.safeCmd(async () => {
        const data = await this.request('/params', { key: String(args.KEY), value: String(args.VALUE) });
        return data.note || '完成';
      });
    }

    async aimTo(args) {
      return this.safeCmd(async () => {
        const data = await this.request('/look', {
          yaw: Number(args.YAW) || 0,
          pitch: Number(args.PITCH) || 0,
        });
        return data.note || '已转向';
      });
    }

    async attack() {
      return this.safeCmd(async () => {
        const data = await this.request('/attack', {});
        return data.note || '完成';
      });
    }

    async mappings() {
      return this.safeAsync(async () => {
        const data = await this.request('/mappings');
        const report = String(data.report || '');
        const lines = report.split('\n').filter(Boolean).slice(0, 6);
        return lines.join(' | ') || '没有报告';
      }, '');
    }

    async pulseNote() {
      return this.safeAsync(async () => {
        const status = await this.status(false);
        const pulse = status.pulse || {};
        return pulse.note || '';
      }, '');
    }

    lastError() {
      return this.lastErr;
    }

    async shutdown() {
      return this.safeCmd(async () => {
        const data = await this.request('/shutdown', {});
        this.connected = false;
        return data.note || '已停止';
      });
    }

    async safeCmd(fn) {
      try {
        return await fn();
      } catch (e) {
        this.lastErr = e && e.message ? e.message : String(e);
        return '注入端错误：' + this.lastErr;
      }
    }

    async safeAsync(fn, fallback) {
      try {
        return await fn();
      } catch (e) {
        this.lastErr = e && e.message ? e.message : String(e);
        return fallback;
      }
    }
  }

  Scratch.extensions.register(new ScratchMc());
})(Scratch);
