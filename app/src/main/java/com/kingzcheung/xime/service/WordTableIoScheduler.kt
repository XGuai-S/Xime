package com.kingzcheung.xime.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 词库持久化的统一串行域。
 *
 * ## 为什么需要它
 *
 * 词频/造词/排序/黑名单五张表的三类操作各自有锁，但**彼此不互斥**：
 *
 * - `load`（读盘 → 发布内存）：读盘期间不持 liveLock，发布时才持
 * - `save`（内存快照 → 写盘 → 三方合并回内存）
 * - `reconcile`（合并外部手改 → 推进基线）
 *
 * 三者穿插会出两类真实缺陷：
 *
 * 1. **删除被复活**：save 捕获 base=1，外部手改到 10 后 `saveFreq` 返回 persisted=10，
 *    但整个 save 还停在其他 Store；此时用户删掉该词，reconcile 把 live 删掉并推进基线；
 *    旧 save 再发布时，合并规则优先采用旧的 persisted=10，该词被写回磁盘。
 * 2. **读取前已脏的内存被覆盖**：磁盘词频 1、内存已上屏到 2 但防抖尚未保存，
 *    load 的快照就是 2，读盘得到 1；整个读取期间内存没再变，
 *    [WordTableReloadGuard] 判等通过，于是用 1 覆盖了 2。
 *
 * ## 做法
 *
 * 所有慢 IO 操作先取 [ioLock] 串行化。本类**只管 IO 串行**，不碰 liveLock：
 * 内存表的读/写由调用方在自己的短临界区里取锁，慢 IO 期间绝不持有它
 * （上屏热路径因此不受影响）。
 */
internal class WordTableIoScheduler {
    /** 慢 IO 串行域：load / save / reconcile 共享。可重入，save 内部再调 reconcile 不会自锁。 */
    private val ioLock = ReentrantLock()

    /** 在 IO 域内执行慢操作。 */
    fun <T> io(block: () -> T): T = ioLock.withLock(block)
}
