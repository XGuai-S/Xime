// SchemeCodeIndex 单元测试（对齐安卓 SchemeCodeIndexTest）。纯逻辑，可独立编译。
#include "../src/core/SchemeCodeIndex.h"

#include <cstdio>
#include <map>
#include <string>
#include <vector>

using namespace xime::core;

namespace {
int fails = 0;

std::string Join(const std::vector<std::string>& v) {
    std::string s;
    for (size_t i = 0; i < v.size(); ++i) {
        if (i) s += ",";
        s += v[i];
    }
    return s;
}

void Expect(const std::string& got, const std::string& want, const char* msg) {
    if (got != want) {
        std::printf("FAIL %s: got=[%s] want=[%s]\n", msg, got.c_str(), want.c_str());
        ++fails;
    }
}
} // namespace

int main() {
    std::map<std::string, std::vector<std::string>> cc = {
        {"\xe7\x8e\xb0", {"xm"}},   // 现
        {"\xe5\x9c\xa8", {"zdut"}}, // 在
        {"\xe7\x9b\xae", {"mu"}},   // 目
        {"\xe5\xbd\x95", {"lue"}},  // 录
        {"\xe9\xab\x98", {"gc"}},   // 高
        {"\xe4\xbd\x8e", {"dir"}},  // 低
        {"\xe6\xbc\x82", {"pnd"}},  // 漂
        {"\xe4\xba\xae", {"llw"}},  // 亮
        {"\xe8\xbe\x93", {"uui"}},  // 输
        {"\xe5\x85\xa5", {"rup"}},  // 入
        {"\xe6\xb3\x95", {"fa"}},   // 法
    };
    Expect(Join(SchemeCodeIndex::ComposeCodes("\xe7\x8e\xb0\xe5\x9c\xa8", cc)),
           "xmzd", "2char-xianzai");
    Expect(Join(SchemeCodeIndex::ComposeCodes("\xe7\x9b\xae\xe5\xbd\x95", cc)),
           "mulu", "2char-mulu");
    Expect(Join(SchemeCodeIndex::ComposeCodes("\xe6\xbc\x82\xe4\xba\xae", cc)),
           "pnll", "2char-piaoliang");
    Expect(Join(SchemeCodeIndex::ComposeCodes("\xe9\xab\x98\xe4\xbd\x8e", cc)),
           "gcdi", "2char-gaodi");
    Expect(Join(SchemeCodeIndex::ComposeCodes(
                    "\xe8\xbe\x93\xe5\x85\xa5\xe6\xb3\x95", cc)),
           "urf,urfa", "3char-shurufa");
    Expect(Join(SchemeCodeIndex::ComposeCodes(
                    "\xe7\x8e\xb0\xe5\x9c\xa8\xe7\x9b\xae\xe5\xbd\x95", cc)),
           "xzml", "4char");
    Expect(Join(SchemeCodeIndex::ComposeCodes(
                    "\xe7\x8e\xb0\xe5\x9c\xa8\xe7\x9b\xae\xe5\xbd\x95\xe9\xab\x98", cc)),
           "xzmg", "5char");

    // 缺字 -> 空
    {
        auto m = cc;
        m.erase("\xe5\xbd\x95"); // 录
        Expect(Join(SchemeCodeIndex::ComposeCodes(
                        "\xe7\x8e\xb0\xe5\x9c\xa8\xe7\x9b\xae\xe5\xbd\x95", m)),
               "", "missing-char");
    }
    Expect(Join(SchemeCodeIndex::ComposeCodes("", cc)), "", "empty-word");

    // buildCharCodes 只认单字条目
    {
        auto built = SchemeCodeIndex::BuildCharCodes(
            {{"\xe7\x8e\xb0\xe5\x9c\xa8", "xmzd"}, {"\xe7\x9b\xae\xe5\xbd\x95", "mulu"}});
        Expect(std::to_string(built.size()), "0", "only-words-no-charcodes");
    }
    // 满四码字典序
    {
        auto built = SchemeCodeIndex::BuildCharCodes({
            {"\xe5\x9c\xa8", "zdut"}, {"\xe5\x9c\xa8", "zdub"},
            {"\xe5\x8f\xaf", "kedk"}, {"\xe7\x8e\xb0", "xmwe"},
            {"\xe7\x8e\xb0", "xmwd"}});
        Expect(Join(built["\xe5\x9c\xa8"]), "zdub,zdut", "zai-sorted");
        Expect(Join(built["\xe5\x8f\xaf"]), "kedk", "ke");
        Expect(Join(built["\xe7\x8e\xb0"]), "xmwd,xmwe", "xian-sorted");
    }
    // 不足四码不收录
    {
        auto built = SchemeCodeIndex::BuildCharCodes(
            {{"\xe4\xba\x86", "l"}, {"\xe7\x9b\xae", "mu"}});
        Expect(std::to_string(built.count("\xe4\xba\x86")), "0", "short-le");
        Expect(std::to_string(built.count("\xe7\x9b\xae")), "0", "short-mu");
    }
    // 单字建码后生成两字词
    {
        auto built = SchemeCodeIndex::BuildCharCodes({
            {"\xe7\x8e\xb0", "xmwe"}, {"\xe5\x9c\xa8", "zdut"},
            {"\xe7\x9b\xae", "mubk"}, {"\xe5\xbd\x95", "luek"}});
        Expect(Join(SchemeCodeIndex::ComposeCodes("\xe7\x8e\xb0\xe5\x9c\xa8", built)),
               "xmzd", "built-xianzai");
        Expect(Join(SchemeCodeIndex::ComposeCodes("\xe7\x9b\xae\xe5\xbd\x95", built)),
               "mulu", "built-mulu");
    }

    if (fails == 0) {
        std::printf("SCHEMECODE_TEST_OK\n");
        return 0;
    }
    std::printf("SCHEMECODE_TEST_FAILS=%d\n", fails);
    return 1;
}
