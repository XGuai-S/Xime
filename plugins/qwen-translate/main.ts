// 多引擎翻译插件（TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js）
//
// 职责划分：
//   插件 = 按「引擎」组装各自的请求体 + 解析各自的响应格式 + 累积译文
//   宿主 = 通用工具面板 + 结果交互自适应（1 条自动上屏替换选区 / 多条候选选择）
//
// 支持的引擎：
//   - qwen      qwen-mt（阿里云百炼，SSE 流式，与旧版行为完全一致）
//   - deepl     DeepL（api-free / api-pro，非流式）
//   - google    Google Cloud Translation v2（非流式）
//   - openai    任意 OpenAI 兼容 chat/completions（SSE 流式）
//
// 兼容性约定（重要）：默认引擎是 qwen，旧用户升级后不配置任何新项即保持原行为；
// 旧配置键（apiKey / baseUrl / model / sourceLang / targetLang）全部沿用，不迁移、不清空。

const KEY_ENGINE = 'engine';
const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_TARGET_LANG = 'targetLang';
const KEY_SOURCE_LANG = 'sourceLang';
// 新增：非 qwen 引擎各自的凭据/地址
const KEY_DEEPL_KEY = 'deeplKey';
const KEY_DEEPL_FREE = 'deeplFree';
const KEY_DEEPL_BASE = 'deeplBaseUrl';
const KEY_GOOGLE_KEY = 'googleKey';
const KEY_GOOGLE_BASE = 'googleBaseUrl';
const KEY_OPENAI_BASE = 'openaiBaseUrl';
const KEY_OPENAI_MODEL = 'openaiModel';

const ENGINE_QWEN = 'qwen';
const ENGINE_DEEPL = 'deepl';
const ENGINE_GOOGLE = 'google';
const ENGINE_OPENAI = 'openai';

const ENGINE_OPTIONS = [
  '千问 qwen-mt|qwen',
  'DeepL|deepl',
  'Google 翻译|google',
  'OpenAI 兼容|openai',
];
const ENGINE_VALUES = ENGINE_OPTIONS.map((o) => o.split('|')[1]);

const DEFAULTS = {
  engine: ENGINE_QWEN,
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  model: 'qwen-mt-flash',
  sourceLang: 'auto',
  targetLang: 'English',
  deeplBaseUrl: 'https://api-free.deepl.com',
  googleBaseUrl: 'https://translation.googleapis.com',
  openaiBaseUrl: 'https://api.openai.com/v1',
  openaiModel: 'gpt-4o-mini',
};

// 面板语言选择行选项："显示名|接口值"。
// qwen-mt 用官方枚举；DeepL 用其 target_lang 代码；Google 用 ISO-639-1。
// 为免维护三套表，这里以 qwen 枚举为主键，运行时映射到各引擎的代码。
const SOURCE_LANGS = [
  '自动检测|auto',
  '简体中文|Chinese',
  'English|English',
  '日本語|Japanese',
  '한국어|Korean',
  'Deutsch|German',
  'Français|French',
  'Italiano|Italian',
  'Português|Portuguese',
  'Español|Spanish',
  'Русский|Russian',
  'العربية|Arabic',
  'ไทย|Thai',
  'Polski|Polish',
  'Tiếng Việt|Vietnamese',
];
const TARGET_LANGS = SOURCE_LANGS.filter((o) => o.split('|')[1] !== 'auto');
const SOURCE_VALUES = SOURCE_LANGS.map((o) => o.split('|')[1]);
const TARGET_VALUES = TARGET_LANGS.map((o) => o.split('|')[1]);

// qwen 枚举 → DeepL target_lang（DeepL 用大写代码，部分语言带区域）
const DEEPL_TARGET: Record<string, string> = {
  Chinese: 'ZH',
  English: 'EN',
  Japanese: 'JA',
  Korean: 'KO',
  German: 'DE',
  French: 'FR',
  Italian: 'IT',
  Portuguese: 'PT-PT',
  Spanish: 'ES',
  Russian: 'RU',
  Arabic: 'AR',
  Thai: 'TH',
  Polish: 'PL',
  Vietnamese: 'VI',
};
// qwen 枚举 → DeepL source_lang（auto 不传）
const DEEPL_SOURCE: Record<string, string> = {
  Chinese: 'ZH',
  English: 'EN',
  Japanese: 'JA',
  Korean: 'KO',
  German: 'DE',
  French: 'FR',
  Italian: 'IT',
  Portuguese: 'PT',
  Spanish: 'ES',
  Russian: 'RU',
  Arabic: 'AR',
  Thai: 'TH',
  Polish: 'PL',
  Vietnamese: 'VI',
};
// qwen 枚举 → Google ISO-639-1
const GOOGLE_LANG: Record<string, string> = {
  auto: 'auto',
  Chinese: 'zh-CN',
  English: 'en',
  Japanese: 'ja',
  Korean: 'ko',
  German: 'de',
  French: 'fr',
  Italian: 'it',
  Portuguese: 'pt',
  Spanish: 'es',
  Russian: 'ru',
  Arabic: 'ar',
  Thai: 'th',
  Polish: 'pl',
  Vietnamese: 'vi',
};

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface ResultItem {
  id: string;
  text: string;
}

let lastContext = '';
let buffer = '';
let generating = false;
let sessionId = -1;

function currentEngine(): string {
  const stored = host.config.get(KEY_ENGINE) || '';
  return ENGINE_VALUES.indexOf(stored) >= 0 ? stored : DEFAULTS.engine;
}

function currentSourceLang(): string {
  const stored = host.config.get(KEY_SOURCE_LANG) || '';
  return SOURCE_VALUES.indexOf(stored) >= 0 ? stored : DEFAULTS.sourceLang;
}

function currentTargetLang(): string {
  const stored = host.config.get(KEY_TARGET_LANG) || '';
  return TARGET_VALUES.indexOf(stored) >= 0 ? stored : DEFAULTS.targetLang;
}

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
}

/** 面板控件行：引擎选择 + 源语言 + 互换 + 目标语言。 */
function buildControlsUi(): XimeUiNode[] {
  return [
    { type: 'select', key: KEY_ENGINE, label: '引擎', value: currentEngine(), options: ENGINE_OPTIONS },
    { type: 'select', key: KEY_SOURCE_LANG, label: '源语言', value: currentSourceLang(), options: SOURCE_LANGS },
    { type: 'button', key: 'swapLang', label: '⇄' },
    { type: 'select', key: KEY_TARGET_LANG, label: '目标语言', value: currentTargetLang(), options: TARGET_LANGS },
  ];
}

/** 去掉末尾斜杠，拼接路径用。 */
function trimSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

/** 取 JSON 字段的字符串值（宿主 QuickJS 无可选链兜底，统一走这里）。 */
function str(v: unknown): string {
  return v === null || v === undefined ? '' : String(v);
}

// ================= 各引擎请求实现 =================

/** qwen-mt：SSE 流式，原文直传 user 消息 + translation_options。 */
async function startQwen(text: string): Promise<boolean> {
  const apiKey = host.config.get(KEY_API_KEY) || '';
  if (apiKey === '') {
    host.logError('未配置 API Key（千问引擎）');
    return false;
  }
  const baseUrl = trimSlash(host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl);
  const model = host.config.get(KEY_MODEL) || DEFAULTS.model;
  const body = new TextEncoder().encode(
    JSON.stringify({
      model: model,
      messages: [{ role: 'user', content: text }],
      translation_options: {
        source_lang: currentSourceLang(),
        target_lang: currentTargetLang(),
      },
      stream: true,
    })
  );
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + apiKey,
  };
  sessionId = await host.http.stream(baseUrl + '/chat/completions', headers, 0, 'POST', body);
  return true;
}

/** OpenAI 兼容 chat/completions：SSE 流式，用于自建网关或其它厂商。 */
async function startOpenAi(text: string): Promise<boolean> {
  const apiKey = host.config.get(KEY_API_KEY) || '';
  const baseUrl = trimSlash(host.config.get(KEY_OPENAI_BASE) || DEFAULTS.openaiBaseUrl);
  const model = host.config.get(KEY_OPENAI_MODEL) || DEFAULTS.openaiModel;
  const target = currentTargetLang();
  const body = new TextEncoder().encode(
    JSON.stringify({
      model: model,
      messages: [
        {
          role: 'system',
          content: '你是翻译引擎。把用户输入翻译成' + target + '，只输出译文，不要解释或加引号。',
        },
        { role: 'user', content: text },
      ],
      stream: true,
    })
  );
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (apiKey !== '') headers['Authorization'] = 'Bearer ' + apiKey;
  sessionId = await host.http.stream(baseUrl + '/chat/completions', headers, 0, 'POST', body);
  return true;
}

/** DeepL：非流式，一次返回。 */
async function translateDeepL(text: string): Promise<string> {
  const key = host.config.get(KEY_DEEPL_KEY) || '';
  if (key === '') {
    host.logError('未配置 DeepL API Key');
    return '';
  }
  const free = (host.config.get(KEY_DEEPL_FREE) || 'true') !== 'false';
  const base = trimSlash(
    host.config.get(KEY_DEEPL_BASE) || (free ? DEFAULTS.deeplBaseUrl : 'https://api.deepl.com')
  );
  const target = DEEPL_TARGET[currentTargetLang()] || 'EN';
  const src = currentSourceLang();
  const form: string[] = ['text=' + encodeURIComponent(text), 'target_lang=' + target];
  if (src !== 'auto' && DEEPL_SOURCE[src]) form.push('source_lang=' + DEEPL_SOURCE[src]);

  const body = new TextEncoder().encode(form.join('&'));
  const headers: Record<string, string> = {
    'Content-Type': 'application/x-www-form-urlencoded',
    Authorization: 'DeepL-Auth-Key ' + key,
  };
  const resp = await host.http.request('POST', base + '/v2/translate', headers, body, 30000);
  const json = JSON.parse(resp.text);
  const list = (json as Record<string, unknown>).translations;
  if (!Array.isArray(list) || list.length === 0) return '';
  return str((list[0] as Record<string, unknown>).text);
}

/** Google Cloud Translation v2：非流式。 */
async function translateGoogle(text: string): Promise<string> {
  const key = host.config.get(KEY_GOOGLE_KEY) || '';
  if (key === '') {
    host.logError('未配置 Google API Key');
    return '';
  }
  const base = trimSlash(host.config.get(KEY_GOOGLE_BASE) || DEFAULTS.googleBaseUrl);
  const target = GOOGLE_LANG[currentTargetLang()] || 'en';
  const src = currentSourceLang();
  const payload: Record<string, unknown> = { q: text, target: target, format: 'text' };
  if (src !== 'auto' && GOOGLE_LANG[src]) payload.source = GOOGLE_LANG[src];

  const body = new TextEncoder().encode(JSON.stringify(payload));
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  const url = base + '/language/translate/v2?key=' + encodeURIComponent(key);
  const resp = await host.http.request('POST', url, headers, body, 30000);
  const json = JSON.parse(resp.text);
  const holder = (json as Record<string, unknown>).data as Record<string, unknown> | undefined;
  if (holder === undefined || holder === null) return '';
  const list = holder.translations;
  if (!Array.isArray(list) || list.length === 0) return '';
  return str((list[0] as Record<string, unknown>).translatedText);
}

/** 统一入口：按当前引擎切换。返回 true 表示走了流式（结果经 SSE 回调累积）。 */
async function translate(text: string): Promise<string> {
  const engine = currentEngine();
  if (engine === ENGINE_DEEPL) return await translateDeepL(text);
  if (engine === ENGINE_GOOGLE) return await translateGoogle(text);
  if (engine === ENGINE_OPENAI) {
    await startOpenAi(text);
    return ''; // 流式：结果由 onData 累积
  }
  await startQwen(text);
  return ''; // 流式
}

// ================= 插件定义（宿主按扩展点路由调用） =================

const plugin = definePlugin({
  // ================= 配置 schema（插件中心设置页表单数据源） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_ENGINE,
          label: '翻译引擎',
          type: 'select',
          defaultValue: DEFAULTS.engine,
          options: ENGINE_OPTIONS,
          helpText: '默认千问；切换到 DeepL / Google 需填各自 API Key',
        },
        // ---- 千问（原有配置，保持不变） ----
        {
          key: KEY_API_KEY,
          label: '千问 API Key',
          type: 'secret',
          placeholder: '输入阿里云百炼 API Key',
          helpText: '阿里云百炼（DashScope）的 API Key',
        },
        {
          key: KEY_BASE_URL,
          label: '千问接口地址',
          type: 'text',
          defaultValue: DEFAULTS.baseUrl,
          helpText: '百炼 OpenAI 兼容模式地址（/compatible-mode/v1 前缀），域名将自动获得联网授权',
        },
        {
          key: KEY_MODEL,
          label: '千问模型',
          type: 'text',
          defaultValue: DEFAULTS.model,
          helpText: 'qwen-mt 系列翻译模型（如 qwen-mt-flash / qwen-mt-plus）',
        },
        // ---- DeepL ----
        {
          key: KEY_DEEPL_KEY,
          label: 'DeepL API Key',
          type: 'secret',
          placeholder: '仅在选择 DeepL 引擎时需要',
          helpText: 'DeepL Free 与 Pro 的 Key 格式相同，会用下方开关决定接口域名',
        },
        {
          key: KEY_DEEPL_FREE,
          label: 'DeepL 免费版',
          type: 'switch',
          defaultValue: 'true',
          helpText: '开启走 api-free.deepl.com；关闭走 api.deepl.com（Pro）',
        },
        {
          key: KEY_DEEPL_BASE,
          label: 'DeepL 接口地址',
          type: 'text',
          defaultValue: DEFAULTS.deeplBaseUrl,
          helpText: '一般无需修改；自建代理时填写',
        },
        // ---- Google ----
        {
          key: KEY_GOOGLE_KEY,
          label: 'Google API Key',
          type: 'secret',
          placeholder: '仅在选择 Google 引擎时需要',
          helpText: 'Google Cloud Translation API v2 的 Key',
        },
        {
          key: KEY_GOOGLE_BASE,
          label: 'Google 接口地址',
          type: 'text',
          defaultValue: DEFAULTS.googleBaseUrl,
          helpText: '一般无需修改；自建代理时填写',
        },
        // ---- OpenAI 兼容 ----
        {
          key: KEY_OPENAI_BASE,
          label: 'OpenAI 兼容地址',
          type: 'text',
          defaultValue: DEFAULTS.openaiBaseUrl,
          helpText: '任意 OpenAI 兼容网关，如 https://api.openai.com/v1',
        },
        {
          key: KEY_OPENAI_MODEL,
          label: 'OpenAI 兼容模型',
          type: 'text',
          defaultValue: DEFAULTS.openaiModel,
          helpText: '如 gpt-4o-mini / deepseek-chat 等',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        // 接受宿主上下文预填：选中文本优先（无选区时宿主兜底剪贴板），都无则为空框手输
        inputText: input.inputText || '',
        items: buildItems(),
        loading: generating,
        ui: buildControlsUi(),
      };
    },

    onInput(input: XimePanelInputEvent): void {
      if (input.key === '') {
        lastContext = input.value || '';
        return;
      }
      if (input.key === KEY_ENGINE && input.value) {
        host.config.set(KEY_ENGINE, input.value);
        return;
      }
      if (input.key === KEY_SOURCE_LANG && input.value) {
        host.config.set(KEY_SOURCE_LANG, input.value);
        return;
      }
      if (input.key === KEY_TARGET_LANG && input.value) {
        host.config.set(KEY_TARGET_LANG, input.value);
        return;
      }
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId === 'swapLang') {
        const src = currentSourceLang();
        // 源语言为自动检测（auto）时没有可交换的目标方向，保持目标语言不变
        if (src !== 'auto') {
          host.config.set(KEY_SOURCE_LANG, currentTargetLang());
          host.config.set(KEY_TARGET_LANG, src);
        }
        return;
      }
      if (input.actionId !== 'generate') return;
      const context = lastContext;
      if (context === '') {
        host.logError('请先输入待翻译内容');
        return;
      }
      if (generating) return;

      generating = true;
      buffer = '';
      sessionId = -1;
      try {
        const sync = await translate(context);
        if (sync !== '') {
          // 非流式引擎（DeepL / Google）：直接出结果
          buffer = sync;
          generating = false;
        }
        // 流式引擎：保持 generating=true，等 onDone/onError 收尾
      } catch (e) {
        host.logError('翻译失败: ' + ((e as Error).message || '未知错误'));
        generating = false;
        sessionId = -1;
      }
    },
  },

  // SSE 回调槽：仅流式引擎（qwen / openai）使用
  sse: {
    onData(sid: number, text: string): void {
      if (sid !== sessionId) return;
      let data: unknown = null;
      try {
        data = JSON.parse(text);
      } catch (e) {
        return;
      }
      if (data === null || typeof data !== 'object') return;
      const choices = (data as Record<string, unknown>).choices;
      if (!Array.isArray(choices) || choices.length === 0) return;
      const delta = (choices[0] as { delta?: { content?: unknown } }).delta;
      if (delta !== undefined && delta !== null && delta.content !== undefined && delta.content !== null) {
        buffer = buffer + String(delta.content);
      }
    },

    onDone(sid: number, fullText: string): void {
      if (sid !== sessionId) return;
      if (fullText !== undefined && fullText !== null && fullText !== '' && buffer === '') {
        buffer = fullText;
      }
      generating = false;
      sessionId = -1;
    },

    onError(sid: number, message: string): void {
      if (sid !== sessionId) return;
      host.logError('翻译请求失败: ' + (message !== null && message !== undefined ? message : '未知错误'));
      generating = false;
      sessionId = -1;
    },
  },
});

export default plugin;
