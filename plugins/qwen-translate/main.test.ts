// 多引擎翻译：请求契约、流式会话、失败恢复与四控件行。
const plugin = (globalThis as any).plugin as Record<string, any>;
type CapturedStream = { id: number; url: string; method: string; body: any };
let captured: CapturedStream | undefined;
let nextSessionId = 1;

function captureStream(): void {
  captured = undefined;
  (host.http as any).stream = async (
    url: string, _headers: Record<string, string>, _timeout: number,
    method: string, body: Uint8Array,
  ): Promise<number> => {
    const id = nextSessionId++;
    captured = { id, url, method, body: JSON.parse(new TextDecoder().decode(body)) };
    return id;
  };
}

function qwenConfig(key = 'sk-test'): void {
  __ximeMock.setConfig('engine', 'qwen');
  __ximeMock.setConfig('apiKey', key);
  __ximeMock.setConfig('sourceLang', 'auto');
  __ximeMock.setConfig('targetLang', 'English');
}

function control(key: string): any {
  const ui = plugin.panel.state({ inputText: '' }).ui as any[];
  const node = ui.find((item) => item.key === key);
  assert.ok(node, '缺少控件 ' + key);
  return node;
}

test('generate 保留 qwen-mt 请求契约并累积流式结果', async () => {
  qwenConfig();
  captureStream();
  plugin.panel.onInput({ key: '', value: '你好世界' });
  await plugin.panel.onAction({ actionId: 'generate' });
  const request = captured!;
  assert.equal(request.method, 'POST');
  assert.ok(request.url.endsWith('/chat/completions'));
  assert.equal(request.body.messages.length, 1);
  assert.equal(request.body.messages[0].role, 'user');
  assert.equal(request.body.messages[0].content, '你好世界');
  assert.equal(request.body.translation_options.source_lang, 'auto');
  assert.equal(request.body.translation_options.target_lang, 'English');
  assert.equal(plugin.panel.state({ inputText: '' }).loading, true);

  // 回调在连接返回会话编号后投递，保持宿主建立流后的调用契约。
  plugin.sse.onData(request.id, '{"choices":[{"delta":{"content":"Hello"}}]}');
  plugin.sse.onData(request.id, '{"choices":[{"delta":{"content":" world"}}]}');
  plugin.sse.onDone(request.id, '');
  const state = plugin.panel.state({ inputText: '' });
  assert.equal(state.items[0].text, 'Hello world');
  assert.equal(state.loading, false);
});

test('缺千问密钥不发请求且退出 loading，补齐配置可重试', async () => {
  qwenConfig('');
  captureStream();
  plugin.panel.onInput({ key: '', value: '内容' });
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(captured, undefined);
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
  __ximeMock.setConfig('apiKey', 'sk-test');
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.ok(captured, '补齐 Key 后必须能重新发起请求');
  plugin.sse.onDone(captured!.id, 'retry result');
  assert.equal(plugin.panel.state({ inputText: '' }).items[0].text, 'retry result');
});

test('swapLang 互换显式语言，自动检测保持原方向', async () => {
  qwenConfig();
  __ximeMock.setConfig('sourceLang', 'English');
  __ximeMock.setConfig('targetLang', 'Japanese');
  await plugin.panel.onAction({ actionId: 'swapLang' });
  assert.equal(host.config.get('sourceLang'), 'Japanese');
  assert.equal(host.config.get('targetLang'), 'English');
  __ximeMock.setConfig('sourceLang', 'auto');
  __ximeMock.setConfig('targetLang', 'Chinese');
  await plugin.panel.onAction({ actionId: 'swapLang' });
  assert.equal(host.config.get('sourceLang'), 'auto');
  assert.equal(host.config.get('targetLang'), 'Chinese');
});

test('旧版自由文本语言值回退到对应控件的合法枚举', () => {
  __ximeMock.setConfig('sourceLang', '自动检测');
  __ximeMock.setConfig('targetLang', '繁體中文');
  for (const key of ['sourceLang', 'targetLang']) {
    const node = control(key);
    assert.ok(node.options.map((option: string) => option.split('|')[1]).indexOf(node.value) >= 0);
  }
});

test('面板预填与 engine sourceLang swapLang targetLang 四控件契约', () => {
  __ximeMock.setConfig('engine', 'invalid-legacy-engine');
  const state = plugin.panel.state({ inputText: '选中的文本' });
  assert.equal(state.inputText, '选中的文本');
  assert.equal(plugin.panel.state({ inputText: '' }).inputText, '');
  assert.deepEqual(state.ui.map((node: any) => node.key), ['engine', 'sourceLang', 'swapLang', 'targetLang']);
  assert.deepEqual(state.ui.map((node: any) => node.type), ['select', 'select', 'button', 'select']);
  assert.equal(control('engine').value, 'qwen');
  assert.deepEqual(control('engine').options.map((option: string) => option.split('|')[1]), ['qwen', 'deepl', 'google', 'openai']);
});

test('settings 保留原千问键并声明其它引擎独立配置', () => {
  const keys = plugin.settings.schema().map((field: any) => field.key);
  for (const key of ['engine', 'apiKey', 'baseUrl', 'model', 'deeplKey', 'googleKey', 'openaiBaseUrl', 'openaiModel']) {
    assert.ok(keys.indexOf(key) >= 0, '缺少配置键 ' + key);
  }
  assert.ok(keys.indexOf('prompt') < 0);
});

test('OpenAI 兼容引擎保持独立请求格式并正常收尾', async () => {
  __ximeMock.setConfig('engine', 'openai');
  __ximeMock.setConfig('apiKey', '');
  __ximeMock.setConfig('openaiBaseUrl', 'https://gateway.invalid/v1/');
  __ximeMock.setConfig('openaiModel', 'fixture-chat');
  __ximeMock.setConfig('targetLang', 'Japanese');
  captureStream();
  plugin.panel.onInput({ key: '', value: '原文' });
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(captured!.url, 'https://gateway.invalid/v1/chat/completions');
  assert.equal(captured!.body.model, 'fixture-chat');
  assert.equal(captured!.body.messages.length, 2);
  assert.equal(captured!.body.messages[0].role, 'system');
  assert.equal(captured!.body.messages[1].content, '原文');
  assert.equal(captured!.body.translation_options, undefined);
  plugin.sse.onDone(captured!.id, '訳文');
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
  assert.equal(plugin.panel.state({ inputText: '' }).items[0].text, '訳文');
});

test('DeepL 缺密钥会收尾，配置后仍可执行非流式请求', async () => {
  __ximeMock.setConfig('engine', 'deepl');
  __ximeMock.setConfig('deeplKey', '');
  __ximeMock.setConfig('sourceLang', 'Chinese');
  __ximeMock.setConfig('targetLang', 'Japanese');
  const before = __ximeMock.httpRequests.length;
  plugin.panel.onInput({ key: '', value: '你好' });
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
  assert.equal(__ximeMock.httpRequests.length, before);
  __ximeMock.setConfig('deeplKey', 'test-deepl');
  __ximeMock.addHttpResponse('POST', 'https://api-free.deepl.com/v2/translate', {
    status: 200, text: '{"translations":[{"text":"こんにちは"}]}',
  });
  await plugin.panel.onAction({ actionId: 'generate' });
  const state = plugin.panel.state({ inputText: '' });
  assert.equal(state.loading, false);
  assert.equal(state.items[0].text, 'こんにちは');
  const request = __ximeMock.httpRequests[__ximeMock.httpRequests.length - 1];
  assert.ok(request.text.indexOf('target_lang=JA') >= 0);
  assert.ok(request.text.indexOf('source_lang=ZH') >= 0);
});

test('Google 空结果完成后可再次请求，不会永久 loading', async () => {
  __ximeMock.setConfig('engine', 'google');
  __ximeMock.setConfig('googleKey', 'test-google');
  __ximeMock.setConfig('sourceLang', 'auto');
  __ximeMock.setConfig('targetLang', 'English');
  __ximeMock.addHttpResponse('POST', 'https://translation.googleapis.com/language/translate/v2?key=test-google', {
    status: 200, text: '{"data":{"translations":[]}}',
  });
  plugin.panel.onInput({ key: '', value: '空结果' });
  const before = __ximeMock.httpRequests.length;
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
  assert.equal(plugin.panel.state({ inputText: '' }).items.length, 0);
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(__ximeMock.httpRequests.length, before + 2);
});

test('SSE 错误后允许重试，旧会话不能污染新译文', async () => {
  qwenConfig();
  captureStream();
  plugin.panel.onInput({ key: '', value: '重试' });
  await plugin.panel.onAction({ actionId: 'generate' });
  const oldId = captured!.id;
  plugin.sse.onError(oldId, 'fixture network error');
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
  await plugin.panel.onAction({ actionId: 'generate' });
  const newId = captured!.id;
  assert.ok(newId !== oldId);
  plugin.sse.onData(oldId, '{"choices":[{"delta":{"content":"stale"}}]}');
  plugin.sse.onData(newId, '{"choices":[{"delta":{"content":"fresh"}}]}');
  plugin.sse.onDone(newId, '');
  assert.equal(plugin.panel.state({ inputText: '' }).items[0].text, 'fresh');
  assert.equal(plugin.panel.state({ inputText: '' }).loading, false);
});
