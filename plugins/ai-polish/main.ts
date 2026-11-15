// AI 润色插件（TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js，SSE 流式）
//
// 职责划分：
//   插件 = 预设指令集组装 prompt + 发起流式请求 + 累积增量文本 + 停止
//   宿主 = 通用工具面板（输入框/候选渲染/选区替换上屏）+ 通用原语：
//     host.http.stream        async SSE 流式（await 建立，失败 reject XimeError）
//     host.http.closeStream   await 主动中断流
//     host.config             配置存储
//     JSON                    原生 JSON.parse/JSON.stringify
//
// 与 ai-write 的区别：
//   1. 面板顶部多一行「动作」选择（润色/改写/精简/扩写/正式化/口语化/纠错/翻译腔），
//      每个动作内置专用 prompt，用户不必每次写指令；
//   2. 输出严格限定为"只给改写结果"，便于宿主直接替换选区；
//   3. 自定义模板仍保留（动作选 custom 时生效），兼容 ai-write 的老用法。

const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_PROMPT = 'prompt';
const KEY_ACTION = 'action';
const KEY_KEEP_LENGTH = 'keepLength';

const ACTIONS = [
  '润色（通顺）|polish',
  '改写（换表达）|rewrite',
  '精简（去冗余）|shorten',
  '扩写（补细节）|expand',
  '正式化|formal',
  '口语化|casual',
  '纠错（错别字/语病）|proofread',
  '自定义指令|custom',
];
const ACTION_VALUES = ACTIONS.map((o) => o.split('|')[1]);
const DEFAULT_ACTION = 'polish';

// 每个动作的系统指令。共同约束放在 COMMON_RULES，避免 8 处重复。
const COMMON_RULES = `
通用要求（必须严格遵守）：
1. 只输出处理后的文本本身，不要开场白（如"好的""以下是"）、不要解释、不要标题、不要结尾客套；
2. 不要使用 markdown 标记（#、*、-、代码块等），需要分段就用换行；
3. 保持原文的语言（中文输出中文、英文输出英文），不要翻译；
4. 忠实保留原文中的专有名词、数字、时间、人名等事实信息，不得编造或改动。`;

const ACTION_PROMPTS: Record<string, string> = {
  polish: `你是中文编辑。请润色下面的文字：修正语病与错别字，让表达更通顺、准确、自然，但不要改变原意和语气强度。${COMMON_RULES}`,
  rewrite: `你是中文编辑。请改写下面的文字：在保持原意不变的前提下换一种表达方式，用词和句式都要有所变化，避免与原句雷同。${COMMON_RULES}`,
  shorten: `你是中文编辑。请精简下面的文字：删掉冗余修饰、重复表述和可有可无的铺垫，保留全部关键信息，让篇幅明显缩短。${COMMON_RULES}`,
  expand: `你是中文编辑。请扩写下面的文字：在保持原意的基础上补充必要的细节、过渡和说明，让内容更完整充实，但不添加原文没有的事实。${COMMON_RULES}`,
  formal: `你是中文编辑。请把下面的文字改写为正式书面语：去掉口语词、网络用语、语气词，使用规范、严谨、得体的表达，适合工作汇报或公文场景。${COMMON_RULES}`,
  casual: `你是中文编辑。请把下面的文字改写为口语化表达：像日常聊天那样自然，可用短句和常见口语词，但不要低俗或过度玩梗。${COMMON_RULES}`,
  proofread: `你是校对员。请只修正下面文字中的错别字、标点 misuse 和明显语病，其余表达尽量保持不变，改动越少越好。${COMMON_RULES}`,
};

const DEFAULTS = {
  baseUrl: '',
  model: '',
  prompt: `你是我的写作助手。请根据下面的要求处理文字，只输出结果本身。
要求：{context}`,
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

function currentAction(): string {
  const stored = host.config.get(KEY_ACTION) || '';
  return ACTION_VALUES.indexOf(stored) >= 0 ? stored : DEFAULT_ACTION;
}

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
}

/** 面板控件行：动作选择（宿主渲染，值经 onInput 回流）。 */
function buildControlsUi(): XimeUiNode[] {
  return [
    { type: 'select', key: KEY_ACTION, label: '动作', value: currentAction(), options: ACTIONS },
  ];
}

/** 按当前动作组装 system prompt；custom 时回落到用户自定义模板。 */
function systemPrompt(context: string): string {
  const action = currentAction();
  if (action === 'custom') {
    const tpl = host.config.get(KEY_PROMPT) || DEFAULTS.prompt;
    return tpl.split('{context}').join(context);
  }
  const head = ACTION_PROMPTS[action] || ACTION_PROMPTS[DEFAULT_ACTION];
  return head + '\n\n待处理文本：\n' + context;
}

// ================= 插件定义（宿主按扩展点路由调用） =================

const plugin = definePlugin({
  // ================= 配置 schema（插件中心设置页表单数据源） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_API_KEY,
          label: 'API Key',
          type: 'secret',
          placeholder: '输入 LLM API Key',
          helpText: 'OpenAI 兼容接口的 API Key',
        },
        {
          key: KEY_BASE_URL,
          label: '接口地址',
          type: 'text',
          defaultValue: DEFAULTS.baseUrl,
          helpText: 'OpenAI 兼容接口地址（/chat/completions 前缀），域名将自动获得联网授权',
        },
        {
          key: KEY_MODEL,
          label: '模型',
          type: 'text',
          defaultValue: DEFAULTS.model,
          helpText: '如 gpt-4o-mini / qwen-plus / deepseek-chat',
        },
        {
          key: KEY_ACTION,
          label: '默认动作',
          type: 'select',
          defaultValue: DEFAULT_ACTION,
          options: ACTIONS,
          helpText: '面板顶部仍可临时切换动作',
        },
        {
          key: KEY_PROMPT,
          label: '自定义模板',
          type: 'textarea',
          defaultValue: DEFAULTS.prompt,
          helpText: '仅当动作选「自定义指令」时生效，{context} 会被替换为面板输入内容',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        // 选中文本优先（宿主无选区时兜底剪贴板），都无则为空框手输
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
      if (input.key === KEY_ACTION && input.value) {
        host.config.set(KEY_ACTION, input.value);
        return;
      }
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId === 'stop') {
        if (sessionId >= 0) {
          try {
            await host.http.closeStream(sessionId);
          } catch (e) {
            host.logError('中断失败: ' + ((e as Error).message || '未知错误'));
          }
          sessionId = -1;
        }
        generating = false;
        return;
      }
      if (input.actionId !== 'generate') return;
      if (generating) return;

      const context = lastContext;
      if (context === '') {
        host.logError('请先输入要处理的文本');
        return;
      }
      const apiKey = host.config.get(KEY_API_KEY) || '';
      if (apiKey === '') {
        host.logError('AI 润色未配置 API Key');
        return;
      }

      buffer = '';
      generating = true;

      const baseUrl = (host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl).replace(/\/+$/, '');
      const model = host.config.get(KEY_MODEL) || DEFAULTS.model;

      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(
        JSON.stringify({
          model: model,
          messages: [
            { role: 'system', content: systemPrompt(context) },
            { role: 'user', content: context },
          ],
          stream: true,
          // qwen3 等推理模型默认先思考再输出，润色场景不需要
          enable_thinking: false,
        })
      );
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + apiKey,
      };
      const url = baseUrl + '/chat/completions';

      try {
        sessionId = await host.http.stream(url, headers, 0, 'POST', body);
      } catch (e) {
        generating = false;
        host.logError('连接被拒绝: ' + ((e as Error).message || '未知错误'));
      }
    },

    onItemClick(_input: XimePanelItemClickEvent): void {
      // 上屏由宿主完成
    },
  },

  // ---- SSE 回调槽（宿主投递；形参用 sid，避免与模块态 sessionId 遮蔽） ----

  sse: {
    onData(sid: number, text: string): void {
      if (sid !== sessionId) return;
      if (text === undefined || text === null || text === '' || text === '[DONE]') return;
      let data: unknown = null;
      try {
        data = JSON.parse(text);
      } catch (e) {
        return;
      }
      if (data === null || typeof data !== 'object') return;
      const obj = data as Record<string, unknown>;
      const choices = obj.choices;
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
      host.logError('请求失败: ' + (message !== null && message !== undefined ? message : '未知错误'));
      generating = false;
      sessionId = -1;
    },
  },
});

export default plugin;
