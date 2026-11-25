"""build-plugins.ps1 的 --with-assets 行为等价测试（M12）。

本机没有 pwsh，无法执行 Windows 脚本。这里做两件事：

1. 用 Python 复刻脚本里的「产物选择 → 备份 → 复制 → 校验 → 删备份/回滚」顺序，
   注入各种失败，断言顺序与回滚语义（等价逻辑层面可执行）；
2. 对 ps1 源码做静态断言，保证脚本结构与上述等价逻辑一致（防止两边漂移）。

缺陷背景：旧实现「先删光 assets 里的 xipk，再从混合了历史版本的产物目录取第一个
复制」——可能投放旧版本，复制失败还会丢掉旧资源。
"""
from __future__ import annotations

import os
from pathlib import Path
import shutil
import tempfile
import time
import unittest

SCRIPT = Path(__file__).with_name("build-plugins.ps1")

ZIP_HEAD = b"PK\x03\x04"


# ───────────────────────── 等价逻辑（与 ps1 的步骤一一对应） ─────────────────────────
class PublishError(Exception):
    pass


def select_fresh_artifacts(release_dir: Path, names, build_started_utc: float):
    """S2：每个内置插件必须恰好命中 1 个本次构建产物（不使用「取第一个」）。"""
    since = build_started_utc - 2  # 与脚本一致的时间戳容差
    selected = []
    for name in names:
        candidates = sorted(
            p for p in release_dir.glob(f"{name}-*.xipk") if p.stat().st_mtime >= since
        )
        if len(candidates) != 1:
            raise PublishError(f"{name}: 期望 1 个新产物，实际 {len(candidates)} 个")
        selected.append(candidates[0])
    return selected


def assert_artifacts_complete(artifacts):
    """S3：本次构建产物独立校验（存在、非空、zip 头正确）。"""
    for artifact in artifacts:
        if not artifact.is_file():
            raise PublishError(f"产物缺失：{artifact}")
        if artifact.stat().st_size <= 0:
            raise PublishError(f"产物为空：{artifact}")
        with artifact.open("rb") as handle:
            if handle.read(2) != b"PK":
                raise PublishError(f"不是有效的 xipk：{artifact}")


def sync_bundled_assets(assets_dir: Path, artifacts, backup_root: Path, fail_at=None, log=None):
    """S4~S7：备份 → 全部复制 → 校验 → 删备份；失败整体回滚。

    fail_at: None / "copy" / "verify"，用于注入失败。
    """
    log = log if log is not None else []
    assets_dir.mkdir(parents=True, exist_ok=True)
    backup_dir = Path(tempfile.mkdtemp(dir=backup_root))
    publish_names = {a.name for a in artifacts}
    backup_ready = False
    try:
        for entry in sorted(assets_dir.iterdir()):
            if entry.is_dir():
                shutil.copytree(entry, backup_dir / entry.name)
            else:
                shutil.copy2(entry, backup_dir / entry.name)
        log.append("backup")
        backup_ready = True

        for artifact in artifacts:
            if fail_at == "copy":
                raise PublishError("注入失败：复制中断")
            shutil.copy2(artifact, assets_dir / artifact.name)
            log.append(f"copy:{artifact.name}")

        for entry in sorted(assets_dir.glob("*.xipk")):
            if entry.name not in publish_names:
                entry.unlink()
                log.append(f"delete-stale:{entry.name}")

        for artifact in artifacts:
            target = assets_dir / artifact.name
            if fail_at == "verify" or not target.is_file() or target.stat().st_size != artifact.stat().st_size:
                raise PublishError(f"发布后校验失败：{artifact.name}")
            log.append(f"verify:{artifact.name}")

        shutil.rmtree(backup_dir)
        log.append("delete-backup")
        backup_ready = False
    except Exception:
        if backup_ready:
            log.append("rollback")
            for entry in sorted(assets_dir.iterdir()):
                if entry.is_dir():
                    shutil.rmtree(entry)
                else:
                    entry.unlink()
            for entry in sorted(backup_dir.iterdir()):
                if entry.is_dir():
                    shutil.copytree(entry, assets_dir / entry.name)
                else:
                    shutil.copy2(entry, assets_dir / entry.name)
            shutil.rmtree(backup_dir)
        raise
    return log


# ─────────────────────────────────── 测试 ───────────────────────────────────
class PublishOrderTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.release = self.root / "build/plugin-release"
        self.assets = self.root / "app/src/main/assets/plugins"
        self.backup_root = self.root / "tmp"
        self.release.mkdir(parents=True)
        self.assets.mkdir(parents=True)
        self.backup_root.mkdir(parents=True)
        self.started = time.time()

    def tearDown(self):
        self._tmp.cleanup()

    def make_artifact(self, name, version, fresh=True, payload=b""):
        path = self.release / f"{name}-{version}.xipk"
        path.write_bytes(ZIP_HEAD + payload)
        if not fresh:
            old = self.started - 3600
            os.utime(path, (old, old))
        return path

    def assets_snapshot(self):
        return {p.name: p.read_bytes() for p in sorted(self.assets.iterdir())}

    # 1) 混合历史产物：只发布本次构建的那一个，绝不「取第一个」
    def test_mixed_history_publishes_fresh_artifact_only(self):
        self.make_artifact("webdav-backup", "2.0.0", fresh=False, payload=b"OLD")
        fresh = self.make_artifact("webdav-backup", "3.0.1", fresh=True, payload=b"NEW")
        (self.assets / "webdav-backup-2.0.0.xipk").write_bytes(ZIP_HEAD + b"ASSET-OLD")

        artifacts = select_fresh_artifacts(self.release, ["webdav-backup"], self.started)
        self.assertEqual([fresh], artifacts)
        assert_artifacts_complete(artifacts)
        log = sync_bundled_assets(self.assets, artifacts, self.backup_root)

        self.assertIn(f"copy:{fresh.name}", log)
        self.assertNotIn("copy:webdav-backup-2.0.0.xipk", log)
        self.assertEqual(
            sorted(p.name for p in self.assets.iterdir()), ["webdav-backup-3.0.1.xipk"]
        )
        self.assertEqual((self.assets / fresh.name).read_bytes(), ZIP_HEAD + b"NEW")

    # 2) 目录里只有历史产物（本次构建没产出）：报错且不动 assets
    def test_stale_only_fails_without_touching_assets(self):
        self.make_artifact("webdav-backup", "2.0.0", fresh=False)
        (self.assets / "keep.xipk").write_bytes(ZIP_HEAD + b"KEEP")
        before = self.assets_snapshot()

        with self.assertRaises(PublishError):
            select_fresh_artifacts(self.release, ["webdav-backup"], self.started)
        self.assertEqual(before, self.assets_snapshot())

    # 3) 同一插件出现两个新产物（版本升级没清旧文件）：拒绝猜测
    def test_two_fresh_candidates_fail_instead_of_guessing(self):
        self.make_artifact("webdav-backup", "3.0.0", fresh=True)
        self.make_artifact("webdav-backup", "3.0.1", fresh=True)
        with self.assertRaises(PublishError):
            select_fresh_artifacts(self.release, ["webdav-backup"], self.started)

    # 4) 产物损坏（非 zip）：校验阶段失败，不进发布
    def test_corrupted_artifact_rejected_before_publish(self):
        bad = self.release / "webdav-backup-3.0.1.xipk"
        bad.write_bytes(b"not-a-zip")
        with self.assertRaises(PublishError):
            assert_artifacts_complete([bad])

    # 5) 复制中途失败：回滚把旧 assets 完整还原
    def test_copy_failure_rolls_back_old_assets(self):
        fresh = self.make_artifact("webdav-backup", "3.0.1", fresh=True)
        (self.assets / "webdav-backup-2.0.0.xipk").write_bytes(ZIP_HEAD + b"OLD")
        (self.assets / "notes.txt").write_text("保留我", encoding="utf-8")
        before = self.assets_snapshot()

        with self.assertRaises(PublishError):
            sync_bundled_assets(self.assets, [fresh], self.backup_root, fail_at="copy")

        self.assertEqual(before, self.assets_snapshot(), "复制失败后旧资源必须完好")

    # 6) 校验失败：同样回滚
    def test_verify_failure_rolls_back_old_assets(self):
        fresh = self.make_artifact("webdav-backup", "3.0.1", fresh=True)
        (self.assets / "webdav-backup-2.0.0.xipk").write_bytes(ZIP_HEAD + b"OLD")
        before = self.assets_snapshot()

        with self.assertRaises(PublishError):
            sync_bundled_assets(self.assets, [fresh], self.backup_root, fail_at="verify")

        self.assertEqual(before, self.assets_snapshot())

    # 7) 成功路径的顺序：备份在最前、删备份在最后（且删旧 xipk 发生在复制之后）
    def test_success_ordering_backup_first_delete_backup_last(self):
        fresh = self.make_artifact("webdav-backup", "3.0.1", fresh=True)
        (self.assets / "webdav-backup-2.0.0.xipk").write_bytes(ZIP_HEAD + b"OLD")
        log = sync_bundled_assets(self.assets, [fresh], self.backup_root)

        self.assertEqual("backup", log[0])
        self.assertEqual(f"copy:{fresh.name}", log[1])
        self.assertIn("delete-stale:webdav-backup-2.0.0.xipk", log)
        self.assertEqual("delete-backup", log[-1])
        self.assertNotIn("rollback", log)
        # 备份目录必须清干净，不留临时垃圾
        self.assertEqual([], list(self.backup_root.iterdir()))

    # 8) 全成功时备份目录被删；失败时也不留备份垃圾
    def test_backup_directory_cleaned_in_both_paths(self):
        fresh = self.make_artifact("webdav-backup", "3.0.1", fresh=True)
        sync_bundled_assets(self.assets, [fresh], self.backup_root)
        self.assertEqual([], list(self.backup_root.iterdir()))

        (self.assets / "webdav-backup-2.0.0.xipk").write_bytes(ZIP_HEAD + b"OLD")
        with self.assertRaises(PublishError):
            sync_bundled_assets(self.assets, [fresh], self.backup_root, fail_at="copy")
        self.assertEqual([], list(self.backup_root.iterdir()))


class ScriptStructureTest(unittest.TestCase):
    """静态断言 ps1 结构与等价逻辑一致（本机无 pwsh，只能做源码级验证）。"""

    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")
        cls.code = "\n".join(
            line for line in cls.source.splitlines() if not line.lstrip().startswith("#")
        )

    def test_no_first_file_heuristic(self):
        # 旧实现的「取首个」启发式必须消失
        self.assertNotIn("Select-Object -First", self.code)
        self.assertNotIn("-First 1", self.code)

    def test_fresh_artifacts_selected_by_build_time_and_uniqueness(self):
        self.assertIn("BUILD_STARTED_UTC", self.code)
        self.assertIn("LastWriteTimeUtc", self.code)
        self.assertIn("-ne 1", self.code, "必须断言「恰好 1 个新产物」")

    def test_artifacts_validated_before_assets_touched(self):
        validate_call = self.code.index("Assert-ArtifactsComplete -Artifacts")
        sync_call = self.code.index("Sync-BundledAssets -AssetsDir")
        self.assertLess(validate_call, sync_call, "必须先独立校验产物，再同步 assets")
        self.assertIn("Get-FreshBuildArtifacts -ReleaseDir", self.code)
        self.assertLess(
            self.code.index("Get-FreshBuildArtifacts -ReleaseDir"), sync_call,
            "发布前必须先确定本次构建的产物集合",
        )

    def test_backup_happens_before_any_delete(self):
        backup = self.code.index("Copy-Item -LiteralPath $_.FullName -Destination $backupDir")
        delete_backup = self.code.index("Remove-Item -LiteralPath $backupDir -Recurse -Force", backup)
        # 备份之后、删备份之前的代码里，只能删除「不在发布集合中的旧 xipk」
        between = self.code[backup:delete_backup]
        self.assertIn("$publishNames -notcontains $_.Name", between)
        # 备份与删备份之间不允许「无条件删除 assets」：每个 Remove-Item 都必须带过滤条件
        for index, line in enumerate(between.splitlines()):
            if "Remove-Item" not in line:
                continue
            context = "\n".join(between.splitlines()[max(0, index - 2): index + 1])
            self.assertIn(
                "notcontains", context,
                f"assets 里出现无条件删除：{line.strip()}",
            )

    def test_rollback_restores_backup_on_failure(self):
        self.assertIn("回滚", self.code)
        rollback = self.code.index("Write-Warning")
        self.assertIn("Copy-Item", self.code[rollback:], "回滚必须把备份拷回 assets")
        self.assertIn("backupReady", self.code, "用标志确保只在备份成功后才回滚")

    def test_verify_after_all_copies(self):
        copy_block = self.code.index("foreach ($artifact in $Artifacts) {\n            Copy-Item")
        verify_block = self.code.index("发布后校验失败：缺少")
        self.assertLess(copy_block, verify_block, "全部复制完成后才校验")


if __name__ == "__main__":
    unittest.main()
