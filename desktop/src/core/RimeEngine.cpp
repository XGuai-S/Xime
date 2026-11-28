// Xime 桌面版核心引擎实现。移植自 Android JNI Rime 类，去掉 Android 依赖。
#include "RimeEngine.h"

#include <rime/service.h>
#include <rime/schema.h>

#include "t9_processor.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
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
    return rime_ ? rime_->is_maintenance_mode() != 0 : false;
}

bool RimeEngine::CreateSession() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!rime_ || !initialized_) {
        return false;
    }
    session_ = rime_->create_session();
    if (session_ != 0) {
        ReapplyPageSizeIfNeeded();
    }
    return session_ != 0;
}

void RimeEngine::DestroySession() {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (rime_ && session_ != 0) {
        rime_->destroy_session(session_);
        session_ = 0;
    }
}

bool RimeEngine::HasSession() const {
    // setup/initialize 未完成时必须拒绝，否则残留 session_id 会在组件系统
    // 尚未建立时触发空指针（Android 端曾因此冷启动崩溃率 >50%）。
    return session_ != 0 && initialized_;
}

bool RimeEngine::ProcessKey(int keycode, int mask) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSession()) {
        return false;
    }
    return rime_->process_key(session_, keycode, mask) != 0;
}

ProcessResult RimeEngine::ProcessKeyAndGetResult(int keycode, int mask) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ProcessResult result;
    if (!HasSession()) {
        return result;
    }
    result.processed = rime_->process_key(session_, keycode, mask) != 0;
    ReadCurrentState(result);
    return result;
}

ProcessResult RimeEngine::ReadResult(bool processed) {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    ProcessResult result;
    result.processed = processed;
    if (!HasSession()) {
        return result;
    }
    ReadCurrentState(result);
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
            result.candidates.push_back(std::move(c));
        }
        result.hasNextPage = !context.menu.is_last_page;
        result.hasPrevPage = context.menu.page_no > 0;
        rime_->free_context(&context);
    } else {
        const char* input = rime_->get_input(session_);
        result.inputText = input ? input : "";
    }

    RIME_STRUCT(RimeStatus, status);
    if (rime_->get_status(session_, &status)) {
        result.isAsciiMode = status.is_ascii_mode != 0;
        rime_->free_status(&status);
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
    if (!HasSession()) {
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
    if (HasSession()) {
        rime_->clear_composition(session_);
    }
}

std::string RimeEngine::CurrentSchema() const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSession()) {
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
    if (!HasSession() || schemaId.empty()) {
        return false;
    }
    return rime_->select_schema(session_, schemaId.c_str()) != 0;
}

bool RimeEngine::IsAsciiMode() const {
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!HasSession()) {
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

void RimeEngine::ReapplyPageSizeIfNeeded() {
    // M1 暂不覆盖每页候选数，沿用方案自带值。
    // Android 端通过内部 rime::Service API 覆盖；桌面版在 M2 设置模块接入。
}

} // namespace core
} // namespace xime
