# librime 1.18.0 特性生效核查

> 来源：只读静态核查（git 对象库 + 源码行号 + docs/librime-1.18.0-port-report.md）
> 结论：**不是"1.18.0 特性都用上生效"**——主体与核心性能改动已生效，但有几处缺口。

## 一、确实用上并生效 ✅

| 特性 | 证据 |
|---|---|
| 官方 1.18.0 主体 | librime git HEAD `e68b9bf9`（`1.18.0-14-ge68b9bf9`）；与官方 tag 仅 26 文件差异，全是 T9 定制/deps |
| glog→spdlog | `logging.h:18-19` glog 兼容宏后端为 spdlog；`.gitmodules` 只留 spdlog；源码零 glog include（残留 deps/glog gitlink 不参与构建）|
| 并行编译（e930f57）| `deployment_tasks.cc:201-286` 两阶段 + CompileScheduler |
| level_db block cache 8MB→2MB | `level_db.cc:21-26` `NewLRUCache(2 << 20)` |
| prism GetValue(string_view)（7beb718）| `prism.h:84`、`prism.cc:268` |
| DictEntryIterator O(log n)（a104ec6）| `dictionary.cc:244-255` pop_heap/push_heap |
| poet 造句优化（210073d）| `poet.cc:285/359` MakeSentenceIncremental*、dedup |
| T9 六项定制 | 音节图缓存/增量查询/增量造句/genuine/空候选守卫/诊断全在，min_end_pos 下界遍布 dict/table/user_dict/poet |
| JNI 64 方法兼容 | Kotlin 64 个 external fun 与 .so 一一对应 |

## 二、声称移植但实际没生效/有缺陷 ❌

### 1. `9777cb2` user_dict 缓存——名义启用、实质失效（重要）
- 宏已定义为 1（`user_dictionary.h:12-14`），`BuildCache` 在 `user_dictionary.cc:327`，`Lookup` 在 `:663-670` 走 `CacheLookup`。
- **但 `:676` 多了一行无条件 `DfsLookup`**（官方 9777cb2 的 `#endif` 后直接 `if (state.query_result.empty())`，无此行）。
- 后果：缓存命中仍跑全量 DB 扫描（未命中时跑两遍），**缓存性能收益归零，甚至更慢**。
- 修复建议：删除 `user_dictionary.cc:676` 的重复 `DfsLookup`，对齐官方实现后重测。

### 2. C++20——从未启用
- `CMakeLists.txt:6` `CMAKE_CXX_STANDARD 17`、`:196` `-std=c++17`；官方 1.18.0 本身也是 C++17。
- 源码无 `std::span/format/concept/co_await` 等 C++20 用法。移植报告中"C++20"表述与代码不符。

### 3. CandidatePreview / streaming_chord / 双角色键 / 输入规范化 / 运行时组件启用——App 层未接入
- C++ 层大多编入（`rime_api.h:172-177`、`streaming_chord_processor.cc/h` 等）。
- 但 Kotlin JNI 无 `candidate_preview` 导出（64 方法无新增），assets schema 无 `streaming_chord`/`dual_role` 配置 → 运行时不会启用。
- 影响：这些是 1.18.0 新能力，App 目前用不上（不影响现有功能）。

### 4. `c95c0d2`/`f70ae3c`/`0ed3f95` 词典优化——无法确认完整保留
- 随 merge 进入，但 `35063f10` 又将 dict 模块统一回 fork 版（T9 链接兼容）；`git diff 1.18.0 HEAD` 显示 dictionary.cc/table.cc 大量差异。
- `ScriptLookupWithExclude` 因 fork dict 语义失败（已知 3 个非 fork 测试失败之一）。

### 5. 部署提速 ↓60%——数据存疑
- 是 WSL x86_64 自测（6450→2598ms），无 arm64 真机复测；且受 9777cb2 缓存缺陷影响，实际收益存疑。

## 三、建议
1. **修 9777cb2 缓存缺陷**（删 `user_dictionary.cc:676` 重复 DfsLookup）——这是唯一影响性能的实际 bug，修复后重测候选/部署时间。
2. 修正移植报告关于"C++20"和"9777cb2 已生效"的矛盾表述。
3. 若要 App 用上 1.18 新特性（candidate preview / streaming_chord），需新增 JNI 方法并在 schema 中启用——属功能增强，非 bug。
