// 词频重排冒烟：验证「上屏计数 → 候选按频数重排 → 按显示位选词回到正确引擎词」。
#include "../src/core/RimeEngine.h"
#include "../src/core/WordFreqStore.h"

#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

namespace fs = std::filesystem;
using xime::core::RimeEngine;
using xime::core::WordFreqStore;

namespace {
int Fail(const char* m) {
    std::printf("WORDFAIL: %s\n", m);
    return 1;
}
} // namespace

int main(int argc, char** argv) {
    std::string dataDir = "desktop/build/data";
    std::string userDir = "desktop/build/word-user";
    if (argc > 1) dataDir = argv[1];
    if (argc > 2) userDir = argv[2];

    std::error_code ec;
    fs::create_directories(userDir, ec);
    // 干净起点
    fs::remove(fs::path(userDir) / "word_freq.txt", ec);

    auto& e = RimeEngine::Instance();
    if (!e.Initialize(userDir, dataDir)) return Fail("init");
    e.StartMaintenance(true);
    for (int i = 0; i < 600 && e.IsMaintaining(); ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    if (e.IsMaintaining()) return Fail("deploy timeout");
    if (!e.CreateSession()) return Fail("session");
    if (!e.SelectSchema("pinyin_simp")) return Fail("schema");

    const char* keys = "nihao";
    auto typeKeys = [&]() {
        xime::core::ProcessResult r;
        for (const char* p = keys; *p; ++p)
            r = e.ProcessKeyAndGetResult(static_cast<unsigned int>(*p), 0);
        return r;
    };

    auto first = typeKeys();
    if (first.candidates.size() < 3) return Fail("需要至少 3 个候选");
    std::printf("WORD_INITIAL_TOP=%s\n", first.candidates.front().text.c_str());
    // 选一个非首的「整词」候选（引擎下标 1，避开可能是前缀的候选），作为高频目标
    const std::string target = first.candidates[1].text;
    const int targetEngineIndex = first.candidates[1].engineIndex;
    std::printf("WORD_TARGET=%s ENGINE_IDX=%d\n", target.c_str(), targetEngineIndex);
    // 清掉这次 composition（不选词）
    e.ClearComposition();

    // 两次上屏该词（每次重新输入 nihao 再按引擎下标选）
    for (int round = 0; round < 2; ++round) {
        auto typed = typeKeys();
        std::printf("WORD_TYPED_%d cands=%zu\n", round, typed.candidates.size());
        auto sel = e.SelectCandidateByEngineIndex(targetEngineIndex);
        std::printf("WORD_SEL_%d processed=%d committed='%s'\n", round,
                    sel.processed ? 1 : 0, sel.committedText.c_str());
        if (sel.committedText.empty())
            return Fail("select by engine index 未上屏");
        std::printf("WORD_COMMIT_%d=%s\n", round, sel.committedText.c_str());
    }

    // 词频文件应已落盘
    if (!fs::exists(fs::path(userDir) / "word_freq.txt"))
        return Fail("word_freq.txt 未生成");

    // 再输入 nihao：目标词应排到显示首位，且 engineIndex 仍指回 2
    auto after = typeKeys();
    if (after.candidates.empty()) return Fail("重排后无候选");
    std::printf("WORD_AFTER_TOP=%s TOP_ENGINE_IDX=%d\n",
                after.candidates.front().text.c_str(),
                after.candidates.front().engineIndex);
    if (after.candidates.front().text != target)
        return Fail("目标词未排到首位");
    if (after.candidates.front().engineIndex != targetEngineIndex)
        return Fail("首位候选 engineIndex 未指回引擎原下标");

    // 通过显示首位（数字键 1 语义）选词，应上屏目标词
    auto shown = e.SelectCandidateByEngineIndex(after.candidates.front().engineIndex);
    if (shown.committedText != target)
        return Fail("按显示位选词上屏文本不符");
    std::printf("WORD_SELECTED=%s\n", shown.committedText.c_str());
    std::printf("WORD_SMOKE_OK\n");
    return 0;
}
