// AutoWordStore 纯逻辑单测（不依赖 librime）。
#include "../src/core/AutoWordStore.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace xime::core;

namespace {
int fails = 0;
void check(bool cond, const char* msg) {
    if (!cond) {
        std::printf("FAIL: %s\n", msg);
        ++fails;
    }
}
bool contains(const std::vector<std::string>& v, const std::string& s) {
    for (const auto& x : v) {
        if (x == s) return true;
    }
    return false;
}
} // namespace

int main() {
    // ── 组合生词 ──
    {
        std::vector<std::string> h = {"小"};
        auto r = AutoWordStore::ComboWithHistory(h, "霞");
        check(contains(r, "小霞"), "小+霞=小霞");

        std::vector<std::string> h2 = {"小", "霞"};
        auto r2 = AutoWordStore::ComboWithHistory(h2, "霞");
        check(contains(r2, "霞霞"), "suffix 霞 -> 霞霞");
        check(contains(r2, "小霞霞"), "suffix 小霞 -> 小霞霞");
        // 短词在前
        check(!r2.empty() && AutoWordStore::CodePointCount(r2.front()) <=
                                  AutoWordStore::CodePointCount(r2.back()),
              "短到长排序");
    }
    // 长度边界：min2 max4
    {
        std::vector<std::string> h = {"一"};
        auto r = AutoWordStore::ComboWithHistory(h, "键");
        check(contains(r, "一键"), "一键 2字通过");
        // 超长：历史整句拼接 >4 字应被拒
        std::vector<std::string> h3 = {"一", "二", "三"};
        auto r3 = AutoWordStore::ComboWithHistory(h3, "四"); // 一二三四 =4字
        check(contains(r3, "一二三四"), "4字通过");
        std::vector<std::string> h4 = {"一", "二", "三", "四"};
        auto r4 = AutoWordStore::ComboWithHistory(h4, "五"); // 一二三四五=5字
        check(!contains(r4, "一二三四五"), "5字拒绝");
    }
    // 词典过滤：多字词典词不作拼块，结果不得内含词典多字词
    {
        auto isDict = [](const std::string& w) { return w == "好多"; };
        std::vector<std::string> h = {"我", "好多"};
        auto r = AutoWordStore::ComboWithHistory(h, "了", 2, 4, isDict, true);
        // "我好多了" 内含"好多" → 排除
        check(!contains(r, "我好多了"), "穿插词典词排除");
        // 单字拼接仍可
        std::vector<std::string> h2 = {"制"};
        auto r2 = AutoWordStore::ComboWithHistory(h2, "式", 2, 4, isDict, true);
        check(contains(r2, "制式"), "纯单字拼接允许");
        // word 本身是词典多字词 → 整条不组合
        std::vector<std::string> h3 = {"我"};
        auto r3 = AutoWordStore::ComboWithHistory(h3, "好多", 2, 4, isDict, true);
        check(r3.empty(), "源词为词典多字词 -> 不组合");
    }
    // ── 收录计数语义 ──
    {
        AutoWordTemp temp;
        check(AutoWordStore::Record(temp, "ab", "你好", 1000, 1), "普通上屏收录");
        check(temp["ab"].size() == 1 && temp["ab"][0].count == 1, "count=1");
        check(AutoWordStore::Record(temp, "ab", "你好", 2000, 1), "再次上屏");
        check(temp["ab"][0].count == 2 && temp["ab"][0].lastSeen == 2000, "count=2,lastSeen刷新");
        // 组合生词 initialCount=0
        check(AutoWordStore::Record(temp, "ab", "拟好", 3000, 0), "组合生词收录 count0");
        int idx = -1;
        for (size_t i = 0; i < temp["ab"].size(); ++i) {
            if (temp["ab"][i].word == "拟好") idx = static_cast<int>(i);
        }
        check(idx >= 0 && temp["ab"][idx].count == 0, "组合生词 count=0");
        check(!AutoWordStore::Record(temp, "ab", "拟好", 4000, 0), "组合生词已存在不计数");
        // 单字拒绝
        check(!AutoWordStore::Record(temp, "ab", "你", 5000, 1), "单字不进造词库");
    }
    // ── 晋升 ──
    {
        AutoWordTemp temp;
        AutoWordFixed fixed;
        const int64_t now = 10000;
        AutoWordStore::Record(temp, "ab", "你好", now, 1); // count=1 达标
        std::vector<std::string> promoted;
        bool changed = AutoWordStore::Promote(temp, fixed, now, "ab", &promoted);
        check(changed, "晋升有变化");
        check(fixed["ab"].count("你好") == 1, "固定库含你好");
        check(fixed["ab"]["你好"] == AutoWordStore::PromoteWeight(1), "权重=count*10");
        check(temp.empty(), "晋升后临时库清空");
        check(contains(promoted, "你好"), "promoted 含你好");
    }
    // ── 超期淘汰 ──
    {
        AutoWordTemp temp;
        const int64_t day = 24LL * 60 * 60 * 1000;
        AutoWordStore::Record(temp, "ab", "拟好", 0, 0); // count0 lastSeen0
        AutoWordFixed fixed;
        std::vector<std::string> promoted;
        // now = 4 天后：lastSeen0 已超 3 天窗口 → 淘汰
        bool changed = AutoWordStore::Promote(temp, fixed, 4 * day, "", &promoted);
        check(changed, "超期淘汰改变");
        check(temp.empty(), "超期临时词被淘汰");
        check(fixed.empty(), "未晋升");
    }
    // ── 候选命中 ──
    {
        AutoWordTemp temp;
        AutoWordFixed fixed;
        AutoWordStore::Record(temp, "ab", "拟好", 1000, 0); // 临时
        fixed["ab"]["你好"] = 100;                            // 固定权重高
        fixed["ab"]["拟号"] = 50;
        auto out = AutoWordStore::CandidatesFor(temp, fixed, "ab", {});
        check(out.size() == 3, "候选 3 个");
        check(!out.empty() && out[0] == "你好", "固定权重最高在前");
        check(out.back() == "拟好", "临时垫底");
        // known 去重
        auto out2 = AutoWordStore::CandidatesFor(temp, fixed, "ab", {"你好"});
        check(!contains(out2, "你好"), "known 排除已有");
    }

    if (fails == 0) {
        std::printf("AUTOWORD_TEST_OK\n");
        return 0;
    }
    std::printf("AUTOWORD_TEST_FAILS=%d\n", fails);
    return 1;
}
