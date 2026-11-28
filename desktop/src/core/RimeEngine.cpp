// Xime 桌面版核心引擎实现。移植自 Android JNI Rime 类，去掉 Android 依赖。
#include "RimeEngine.h"

#include <rime/service.h>
#include <rime/schema.h>

#include "t9_processor.h"
#include "PathUtil.h"
#include "WordFreqStore.h"
#include "SchemeCodeIndex.h"
#include "plugins/PluginHost.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>

#ifndef XIME_RIME_MODULES
#define XIME_RIME_MODULES 1
#endif

#if XIME_RIME_MODULES
extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void rime_require_module_predict();
extern void rime_require_module_t9();
#endif

namespace xime {
namespace core {

namespace {
std::mutex g_engine_mutex;
}

RimeEngine& RimeEngine::Instance() {
    static RimeEngine instance;
    return instance;
}

bool RimeEngine::Initialize(const std::string& userDataDir,
                            const std::string& sharedDataDir) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    rime_ = rime_get_api();
    if (!rime_) {
        return false;
    }

#if XIME_RIME_MODULES
    rime_require_module_lua();
    rime_require_module_octagram();
    rime_require_module_predict();
    rime_require_module_t9();
#endif

    userDataDir_ = userDataDir;
    sharedDataDir_ = sharedDataDir;
    // 词频表与 xime 的其它用户数据同目录，启动即载入。
    WordFreqStore::Instance().SetUserDir(userDataDir);
    WordFreqStore::Instance().Load();
    const std::string logDir = userDataDir + "/logs";

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = sharedDataDir.c_str();
    traits.user_data_dir = userDataDir.c_str();
    traits.log_dir = logDir.c_str();
    traits.min_log_level = 1;
    traits.app_name = "xime.desktop";
    traits.distribution_name = "Xime";
    traits.distribution_code_name = "xime";
    traits.distribution_version = "3.4.70";

    rime_->setup(&traits);
    rime_->initialize(&traits);
    initialized_ = true;
    return true;
}

bool RimeEngine::StartMaintenance(bool full) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!rime_ || !initialized_) {
        return false;
    }
    return rime_->start_maintenance(full) != 0;
}

bool RimeEngine::IsMaintaining() const {
    // 加锁：rime_ 可能在 Initialize/析构期间被改动，而本函数会被按键线程
    // 在部署等待循环里反复轮询。
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return rime_ ? rime_->is_maintenance_mode() != 0 : false;
}

bool RimeEngine::IsDeployed() const {
    std::string userDir;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        userDir = userDataDir_;
    }
    if (userDir.empty()) {
        return false;
    }
    // 用宽路径构造 filesystem::path：窄字符会按 ANSI 代码页解释，
    // 中文用户名（C:\Users\张三\...）下 is_directory 直接返回 false，
    // 于是永远判定"未部署"，每次激活都重新部署并冻结按键线程。
    const std::wstring wdir = Utf8ToWidePath(userDir);
    if (wdir.empty()) {
        return false;
    }
    // librime 部署产物落在 <user>/build/。注意该目录可能由 rime 提前创建但为空，
    // 因此必须看到真正的编译产物（*.bin，如 default.prism.bin）才算已部署，
    // 否则会把"空目录"误判成已部署，从此再也不触发部署、永远没有候选。
    std::error_code ec;
    const std::filesystem::path buildDir =
        std::filesystem::path(wdir) / L"build";
    if (!std::filesystem::is_directory(buildDir, ec)) {
        return false;
    }
    for (std::filesystem::directory_iterator it(buildDir, ec), end; !ec && it != end;
         it.increment(ec)) {
        if (!it->is_regular_file(ec)) {
            continue;
        }
        const std::string name = it->path().filename().string();
        if (name.size() > 4 && name.compare(name.size() - 4, 4, ".bin") == 0) {
            return true;
        }
    }
    return false;
}

bool RimeEngine::CreateSession() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!rime_ || !initialized_) {
        return false;
    }
    // TSF 会为每个激活本 TIP 的线程各建一个 XimeTextService 实例，但引擎是
    // 进程级单例。若这里无条件重建 session_，实例 B 会覆盖实例 A 正在用的会话，
    // 之后 A 的按键全部作用在 B 的会话上、A 的 Deactivate 又会把 B 的会话销毁。
    // 故改为引用计数：已有会话就复用并加计数，最后一个实例销毁时才真正释放。
    if (session_ != 0) {
        ++sessionRefs_;
        return true;
    }
    session_ = rime_->create_session();
    if (session_ != 0) {
        sessionRefs_ = 1;
        ReapplyPageSizeIfNeeded();
        char buffer[256] = {0};
        if (rime_->get_current_schema(session_, buffer, sizeof(buffer))) {
            BuildAutoIndexLocked(buffer);
        }
    }
    return session_ != 0;
}

void RimeEngine::DestroySession() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    // 引用计数归零才真正销毁（见 CreateSession 说明）。
    if (sessionRefs_ > 0) {
        --sessionRefs_;
    }
    if (sessionRefs_ > 0) {
        return;
    }
    if (rime_ && session_ != 0) {
        rime_->destroy_session(session_);
        session_ = 0;
    }
    // 会话结束时强制落盘一次：上屏路径已改为节流保存，这里补上最终写入，
    // 保证退出/停用时不丢最近的词频。
    if (wordFreqDirty_) {
        WordFreqStore::Instance().Save();
        wordFreqDirty_ = false;
    }
}

bool RimeEngine::HasSessionLocked() const {
    return session_ != 0 && initialized_;
}

bool RimeEngine::HasSession() const {
    // setup/initialize 未完成时必须拒绝，否则残留 session_id 会在组件系统
    // 尚未建立时触发空指针（Android 端曾因此冷启动崩溃率 >50%）。
    // 对外入口加锁（DestroySession/Initialize 在锁内改这些字段，本函数会被
    // 按键线程反复轮询，与部署线程并发）；类内部调用一律用 HasSessionLocked，
    // 否则会自死锁（std::mutex 不可重入）。
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return HasSessionLocked();
}

bool RimeEngine::ProcessKey(int keycode, int mask) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return false;
    }
    const bool processed = rime_->process_key(session_, keycode, mask) != 0;
    FlushT9IfActive();
    return processed;
}

ProcessResult RimeEngine::ProcessKeyAndGetResult(int keycode, int mask) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ProcessResult result;
    if (!HasSessionLocked()) {
        return result;
    }
    // 词频记录需要在 commit 前拿到「当时的编码」：process_key 之后 composition 往往已清空。
    const char* preInputRaw = rime_->get_input(session_);
    const std::string preInput = preInputRaw ? preInputRaw : "";
    result.processed = rime_->process_key(session_, keycode, mask) != 0;
    FlushT9IfActive();
    ReadCurrentState(result);
    if (!result.committedText.empty() && !result.isAsciiMode &&
        WordFreqStore::IsCountableWord(result.committedText)) {
        WordFreqStore::Instance().RecordCommit(preInput, result.committedText);
        // 不能每次上屏都在引擎锁内做全表磁盘写：词频表随使用增长，这里会变成
        // 打一个词写一遍全表，期间所有引擎入口（含语言栏查 ascii 状态）被阻塞。
        // 改为脏标记 + 节流落盘，DestroySession/停用/退出时强制补写。
        // 注意：词频本身在 RecordCommit 里已进内存表，读侧（重排）不受影响；
        // 这里只推迟"写文件"，不推迟"生效"。
        wordFreqDirty_ = true;
        const auto now = std::chrono::steady_clock::now();
        if (lastWordFreqSave_.time_since_epoch().count() == 0 ||
            now - lastWordFreqSave_ >= std::chrono::seconds(2)) {
            WordFreqStore::Instance().Save();
            lastWordFreqSave_ = now;
            wordFreqDirty_ = false;
        }
    }
    ApplyPluginTransform(result);
    return result;
}

void RimeEngine::SetPluginTransform(PluginTransform fn) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    pluginTransform_ = std::move(fn);
}

void RimeEngine::SetPluginTransformFull(PluginTransformFull fn) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    pluginTransformFull_ = std::move(fn);
}

void RimeEngine::ClearPluginTransforms() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    pluginTransform_ = nullptr;
    pluginTransformFull_ = nullptr;
}

void RimeEngine::ApplyPluginTransform(ProcessResult& result) {
    if (result.isAsciiMode || result.candidates.empty()) {
        return;
    }
    // 完整契约优先：支持新增候选与 engineIndex 重映射。
    if (pluginTransformFull_) {
        PluginTransformContext ctx;
        ctx.inputText = result.inputText;
        ctx.preedit = result.preeditText;
        ctx.asciiMode = result.isAsciiMode;
        ctx.candidates = result.candidates;
        pluginTransformFull_(ctx);
        std::vector<Candidate> rebuilt;
        rebuilt.reserve(ctx.candidates.size());
        for (const auto& c : ctx.candidates) {
            Candidate out;
            if (c.engineIndex >= 0 &&
                c.engineIndex < static_cast<int>(result.candidates.size())) {
                // 引擎候选：保留原候选的 comment/engineIndex，仅用插件可能的 text 覆盖。
                out = result.candidates[c.engineIndex];
                if (!c.text.empty()) {
                    out.text = c.text;
                }
            } else {
                // 新增文本候选。
                out.text = c.text;
                out.comment = c.comment;
                out.engineIndex = -1;
            }
            rebuilt.push_back(std::move(out));
        }
        result.candidates.swap(rebuilt);
        return;
    }
    if (!pluginTransform_) {
        return;
    }
    std::vector<std::string> texts;
    texts.reserve(result.candidates.size());
    for (const auto& c : result.candidates) {
        texts.push_back(c.text);
    }
    const std::vector<std::string> out = pluginTransform_(texts);
    if (out.size() == result.candidates.size()) {
        // 就位变换：保留每个候选原有的引擎下标，选词仍落回正确引擎词。
        for (size_t i = 0; i < out.size(); ++i) {
            result.candidates[i].text = out[i];
        }
    }
    // 长度变化的变换不在此处理（会破坏引擎下标映射），保持原候选。
}

ProcessResult RimeEngine::SelectCandidateByEngineIndex(int engineIndex) {
    // 引擎页内数字选词：下标 0..8 -> 数字键 1..9，9 -> 0（仅当前页 0）。
    // 走 process_key 数字通道，与正常按键一致（能触发完整 commit 与词频记录）。
    unsigned int vk = 0;
    if (engineIndex >= 0 && engineIndex <= 8) {
        vk = static_cast<unsigned int>('1' + engineIndex);
    } else if (engineIndex == 9) {
        vk = '0';
    }
    if (vk == 0) {
        return ProcessResult{};
    }
    return ProcessKeyAndGetResult(vk, 0);
}

void RimeEngine::FlushT9IfActive() {
    // T9 处理器采用异步 flush 模型：processKey 内只标记待发送内容
    // （SendToRime），需在读取状态前显式执行，才能让引擎真正 compose。
    // 与 Android JNI 的 nativeT9FlushRimeInput 保持一致。
    if (rime::T9Processor* t9 = rime::T9ProcessorRequire()) {
        t9->FlushRimeInput();
    }
}

ProcessResult RimeEngine::ReadResult(bool processed) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ProcessResult result;
    result.processed = processed;
    if (!HasSessionLocked()) {
        return result;
    }
    ReadCurrentState(result);
    ApplyPluginTransform(result);
    return result;
}

void RimeEngine::ReadCurrentState(ProcessResult& result) const {
    RIME_STRUCT(RimeCommit, commit);
    if (rime_->get_commit(session_, &commit)) {
        result.committedText = commit.text ? commit.text : "";
        rime_->free_commit(&commit);
    }

    RIME_STRUCT(RimeContext, context);
    if (rime_->get_context(session_, &context)) {
        const char* input = rime_->get_input(session_);
        result.inputText = input ? input : "";
        result.preeditText =
            context.composition.preedit ? context.composition.preedit : "";
        result.caretPos = static_cast<int>(rime_->get_caret_pos(session_));
        result.preeditCursorPos = context.composition.cursor_pos;

        for (int i = 0; i < context.menu.num_candidates; ++i) {
            Candidate c;
            c.text = context.menu.candidates[i].text
                         ? context.menu.candidates[i].text
                         : "";
            c.comment = context.menu.candidates[i].comment
                            ? context.menu.candidates[i].comment
                            : "";
            c.engineIndex = i;
            result.candidates.push_back(std::move(c));
        }
        result.hasNextPage = !context.menu.is_last_page;
        result.hasPrevPage = context.menu.page_no > 0;
        rime_->free_context(&context);

        // 词频重排：仅非 ascii、非 T9、有输入编码时，按上屏频数降序显示。
        // 重排后候选对象自带 engineIndex，点选据此回到引擎原始词。
        const bool t9Active = rime::T9ProcessorRequire() != nullptr;
        if (!result.isAsciiMode && !t9Active && !result.inputText.empty() &&
            !result.candidates.empty()) {
            std::vector<std::string> texts;
            texts.reserve(result.candidates.size());
            for (const auto& c : result.candidates) {
                texts.push_back(c.text);
            }
            const std::vector<int> order =
                WordFreqStore::Instance().ComputeDisplayOrder(result.inputText,
                                                              texts);
            if (!order.empty()) {
                std::vector<Candidate> reordered;
                reordered.reserve(order.size());
                for (int idx : order) {
                    reordered.push_back(result.candidates[idx]);
                }
                result.candidates.swap(reordered);
            }
        }
    } else {
        const char* input = rime_->get_input(session_);
        result.inputText = input ? input : "";
    }

    RIME_STRUCT(RimeStatus, status);
    if (rime_->get_status(session_, &status)) {
        result.isAsciiMode = status.is_ascii_mode != 0;
        rime_->free_status(&status);
    }

    // 自动造词候选：用当前方案单字码给临时造词合成编码，命中当前输入则追加为候选
    // （engineIndex=-1 表示非引擎词，选中直接上屏文本）。仅非 ascii、非 T9 时。
    const bool t9ForAuto = rime::T9ProcessorRequire() != nullptr;
    if (!result.isAsciiMode && !t9ForAuto && !result.inputText.empty() &&
        !charCodes_.empty() && !autoTempWords_.empty()) {
        for (const auto& w : autoTempWords_) {
            const auto codes = SchemeCodeIndex::ComposeCodes(w, charCodes_);
            bool hit = false;
            for (const auto& c : codes) {
                if (c == result.inputText) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                continue;
            }
            bool exists = false;
            for (const auto& c : result.candidates) {
                if (c.text == w) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                Candidate autoCand;
                autoCand.text = w;
                autoCand.comment = "造词";
                autoCand.engineIndex = -1;
                result.candidates.push_back(std::move(autoCand));
            }
        }
    }

    // T9 快照：仅当 T9 处理器活跃时填充。
    if (rime::T9Processor* t9 = rime::T9ProcessorRequire()) {
        result.t9PanelState = t9->GetLeftPanelState();
        std::string digits;
        const std::string& s = result.t9PanelState;
        size_t p = 0;
        for (int i = 0; i < 4 && p != std::string::npos; ++i) {
            p = s.find(';', p + 1);
        }
        if (p != std::string::npos) {
            const size_t end = s.find(';', p + 1);
            digits = s.substr(p + 1, end - p - 1);
        }
        if (digits.empty()) {
            for (char ch : result.inputText) {
                if (ch >= '0' && ch <= '9') {
                    digits += ch;
                }
            }
        }
        std::vector<std::string> options;
        t9->GetFirstSyllableOptions(digits, 20, options);
        for (size_t i = 0; i < options.size(); ++i) {
            if (i > 0) {
                result.t9SyllableOptions += ",";
            }
            result.t9SyllableOptions += options[i];
        }
    }
}

bool RimeEngine::SetInput(const std::string& input) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return false;
    }
    if (input.empty()) {
        rime_->clear_composition(session_);
        return true;
    }
    return rime_->set_input(session_, input.c_str()) != 0;
}

void RimeEngine::ClearComposition() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (HasSessionLocked()) {
        rime_->clear_composition(session_);
    }
}

ProcessResult RimeEngine::ChangePage(bool backward) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ProcessResult result;
    if (!HasSessionLocked()) {
        return result;
    }
    result.processed = rime_->change_page(session_, backward ? 1 : 0) != 0;
    FlushT9IfActive();
    ReadCurrentState(result);
    return result;
}

std::string RimeEngine::CurrentSchema() const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return "";
    }
    char buffer[256] = {0};
    if (rime_->get_current_schema(session_, buffer, sizeof(buffer))) {
        return std::string(buffer);
    }
    return "";
}

bool RimeEngine::SelectSchema(const std::string& schemaId) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked() || schemaId.empty()) {
        return false;
    }
    const bool ok = rime_->select_schema(session_, schemaId.c_str()) != 0;
    if (ok) {
        BuildAutoIndexLocked(schemaId);
    }
    return ok;
}

std::vector<std::string> RimeEngine::SchemaList() const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    std::vector<std::string> ids;
    if (!HasSessionLocked()) {
        return ids;
    }
    RimeSchemaList list = {};
    if (!rime_->get_schema_list(&list)) {
        return ids;
    }
    for (size_t i = 0; i < list.size; ++i) {
        if (list.list[i].schema_id) {
            ids.push_back(list.list[i].schema_id);
        }
    }
    rime_->free_schema_list(&list);
    return ids;
}

std::string RimeEngine::CycleSchema() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return "";
    }
    RimeSchemaList list = {};
    if (!rime_->get_schema_list(&list)) {
        return "";
    }
    std::vector<std::string> ids;
    for (size_t i = 0; i < list.size; ++i) {
        if (list.list[i].schema_id) {
            ids.push_back(list.list[i].schema_id);
        }
    }
    rime_->free_schema_list(&list);
    if (ids.empty()) {
        return "";
    }
    const std::string current = CurrentSchemaLocked();
    size_t idx = 0;
    for (size_t i = 0; i < ids.size(); ++i) {
        if (ids[i] == current) {
            idx = (i + 1) % ids.size();
            break;
        }
    }
    const std::string next = ids[idx];
    if (rime_->select_schema(session_, next.c_str())) {
        BuildAutoIndexLocked(next);
        return next;
    }
    return "";
}

std::string RimeEngine::CurrentSchemaLocked() const {
    char buffer[256] = {0};
    if (rime_->get_current_schema(session_, buffer, sizeof(buffer))) {
        return std::string(buffer);
    }
    return "";
}

void RimeEngine::BuildAutoIndex(const std::string& schemaId) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    BuildAutoIndexLocked(schemaId);
}

void RimeEngine::BuildAutoIndexLocked(const std::string& schemaId) {
    charCodes_.clear();
    if (schemaId.empty()) {
        return;
    }
    // 方案词典源文件名通常与方案 id 同名（wubi86 / pinyin_simp …），
    // 在共享数据目录（DLL 同级 data）中查找 .dict.yaml。
    const std::string path = sharedDataDir_ + "/" + schemaId + ".dict.yaml";
    std::ifstream in(path, std::ios::binary);
    if (!in) {
        return;
    }
    std::vector<std::pair<std::string, std::string>> entries;
    std::string line;
    bool inBody = false;
    while (std::getline(in, line)) {
        if (!inBody) {
            if (line == "..." || line.rfind("...", 0) == 0) {
                inBody = true;
            }
            continue;
        }
        if (line.empty() || line[0] == '#') {
            continue;
        }
        // 每行： 词\t码[\t权重]
        const size_t t1 = line.find('\t');
        if (t1 == std::string::npos) {
            continue;
        }
        const std::string word = line.substr(0, t1);
        // 仅保留单字（单码点）条目，减少内存与后续过滤量。
        if (SchemeCodeIndex::CodePointStrings(word).size() != 1) {
            continue;
        }
        const size_t t2 = line.find('\t', t1 + 1);
        const std::string code =
            line.substr(t1 + 1, (t2 == std::string::npos ? std::string::npos
                                                          : t2 - t1 - 1));
        entries.emplace_back(word, code);
    }
    charCodes_ = SchemeCodeIndex::BuildCharCodes(entries);
}

void RimeEngine::SetAutoTempWords(const std::vector<std::string>& words) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    autoTempWords_ = words;
}

std::vector<std::string> RimeEngine::ComposeWordCodes(const std::string& word) const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    return SchemeCodeIndex::ComposeCodes(word, charCodes_);
}

bool RimeEngine::IsAsciiMode() const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return false;
    }
    RIME_STRUCT(RimeStatus, status);
    if (rime_->get_status(session_, &status)) {
        const bool ascii = status.is_ascii_mode != 0;
        rime_->free_status(&status);
        return ascii;
    }
    return false;
}

bool RimeEngine::ToggleAsciiMode() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSessionLocked()) {
        return false;
    }
    const bool current = rime_->get_option(session_, "ascii_mode") != 0;
    rime_->set_option(session_, "ascii_mode", current ? 0 : 1);
    return !current;
}

void RimeEngine::ReapplyPageSizeIfNeeded() {
    if (pageSize_ <= 0 || session_ == 0 || !rime_) {
        return;
    }
    char buf[256] = {0};
    if (rime_->get_current_schema(session_, buf, sizeof(buf))) {
        ApplyPageSizeOverrideLocked(buf);
    }
}

bool RimeEngine::SetPageSize(int pageSize) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!rime_ || !initialized_ || pageSize <= 0) {
        return false;
    }
    pageSize_ = pageSize;
    char buf[256] = {0};
    if (session_ != 0 && rime_->get_current_schema(session_, buf, sizeof(buf))) {
        ApplyPageSizeOverrideLocked(buf);
    }
    return true;
}

void RimeEngine::ApplyPageSizeOverrideLocked(const std::string& schemaId) {
    if (!rime_ || pageSize_ <= 0 || schemaId.empty()) {
        return;
    }
    RimeConfig config;
    if (rime_->schema_open(schemaId.c_str(), &config)) {
        rime_->config_set_int(&config, "menu/page_size", pageSize_);
        rime_->config_close(&config);
    }
    if (session_ == 0) {
        return;
    }
    rime::Service& service = rime::Service::instance();
    auto session = service.GetSession(static_cast<rime::SessionId>(session_));
    if (!session) {
        return;
    }
    auto schema = session->schema();
    if (!schema || schema->schema_id() != schemaId) {
        return;
    }
    if (!initialized_) {
        return;
    }
    // 重建 Schema 以让构造时读取的 menu/page_size 生效（引擎未就绪时跳过，值已写入配置）。
    session->ApplySchema(new rime::Schema(schema->schema_id()));
}

} // namespace core
} // namespace xime
