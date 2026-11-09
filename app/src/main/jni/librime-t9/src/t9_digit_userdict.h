#ifndef T9_DIGIT_USERDICT_H_
#define T9_DIGIT_USERDICT_H_

#include <stdint.h>
#include <string>
#include <vector>

#include "t9_digit_userdict_core.h"

namespace rime {

class LevelDb;

// 九键数字序列用户词典（数字序列 → 用户词召回层）。
// 与 RIME pinyin 用户词典并存：场景A 走 RIME userdb，场景B/C 走 T9 侧。
// 存储：librime LevelDb，路径 <user_data_dir>/t9_digit.userdb。
// 数据格式见 T9DigitUserDictCore（纯算法核心，独立单测）。
class T9DigitUserDict {
 public:
  static T9DigitUserDict& Instance();

  // 设置持久化路径并打开 leveldb（仅首次调用生效）。
  static void SetFilePath(const std::string& path);

  // 释放 / 重开 leveldb 句柄（成对使用）。
  //
  // librime 的 UserDictManager（备份、还原、导入导出、user_dict_sync）要在**同进程**
  // 再打开一次 `<user_data_dir>/t9_digit.userdb`，而 leveldb 的 PosixLockTable 对同进程
  // 二次 Open 直接报 "lock ... already held by process" ⇒ 调用方必须在这些操作前后
  // ReleaseDb() / AcquireDb()。释放期间 T9 词条写入降级为仅内存（ApplyOps 容忍 db 缺失），
  // 故成对调用窗口内不应发生输入提交（现网由 rimeLock 保证）。
  static void ReleaseDb();
  static void AcquireDb();

  // 从 RIME input 构建查找键（"54482" / "54482:ji"）。
  static std::string BuildLookupKey(const std::string& rime_input);

  // 关闭并重新打开 leveldb，将 memtable 刷为 .ldb 文件。
  static void CompactDb();

  void Memorize(const std::string& digits, const std::string& text,
                const std::string& pinyin);
  void Forget(const std::string& digits, const std::string& text);
  std::vector<T9DigitUserDictCore::Entry> Lookup(const std::string& digits,
                                                 size_t limit);

  bool loaded() const { return loaded_; }
  // 全局 tick（权重公式 FormulaP 的 present_tick 口径，与 RIME CreateDictEntry 对齐）。
  TickCount tick() const { return core_.tick(); }

 private:
  T9DigitUserDict() = default;
  T9DigitUserDict(const T9DigitUserDict&) = delete;
  T9DigitUserDict& operator=(const T9DigitUserDict&) = delete;

  bool OpenDb(const std::string& path);
  void LoadFromDb();
  void ApplyOps(const std::vector<T9DigitUserDictCore::StorageOp>& ops);
  // 关闭并重新打开 leveldb，刷 memtable 为 .ldb 文件。
  void Compact();

  T9DigitUserDictCore core_;
  LevelDb* db_ = nullptr;
  bool loaded_ = false;
  // 持久化路径（ReleaseDb/AcquireDb 重开时要用，SetFilePath 记录）
  std::string path_;
};

}  // namespace rime

#endif  // T9_DIGIT_USERDICT_H_