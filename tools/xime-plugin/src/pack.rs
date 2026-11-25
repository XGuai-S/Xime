use std::io::Write;
use std::path::{Path, PathBuf};

use zip::write::SimpleFileOptions;

use crate::manifest::Manifest;

/// 把插件产物目录打包为 `<name>-<version>.xipk`（xipk 即 zip，内容为产物目录相对路径）。
///
/// 先写同目录临时文件，成功后再原子替换目标包：打包失败时旧包原样保留。
pub fn pack_plugin(
    out_dir: &Path,
    manifest: &Manifest,
    xipk_dir: &Path,
) -> anyhow::Result<PathBuf> {
    std::fs::create_dir_all(xipk_dir)
        .map_err(|e| anyhow::anyhow!("创建打包目录失败 {}: {e}", xipk_dir.display()))?;

    let plugin_name = out_dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .ok_or_else(|| anyhow::anyhow!("无法解析插件目录名: {}", out_dir.display()))?;

    let xipk_path = xipk_dir.join(format!("{plugin_name}-{}.xipk", manifest.version));
    // 临时文件与目标同目录，保证 rename 原子性
    let tmp_path = xipk_dir.join(format!(
        ".{plugin_name}-{}.xipk.tmp-{}",
        manifest.version,
        std::process::id()
    ));
    // 上次异常退出可能残留临时文件，先清掉
    let _ = std::fs::remove_file(&tmp_path);

    let file = std::fs::File::create(&tmp_path)
        .map_err(|e| anyhow::anyhow!("创建 xipk 失败 {}: {e}", tmp_path.display()))?;
    let mut zip = zip::ZipWriter::new(file);
    // 最高压缩级别（脚本 + 资源；图片等已压缩格式收益有限，但文本收益明显）
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .compression_level(Some(9));

    // 写入失败：丢弃临时文件并报错，旧包不动
    let write_result = add_dir(&mut zip, out_dir, out_dir, options).and_then(|()| {
        zip.finish()
            .map(|_| ())
            .map_err(|e| anyhow::anyhow!("写入 xipk 失败: {e}"))
    });
    if let Err(err) = write_result {
        let _ = std::fs::remove_file(&tmp_path);
        return Err(err);
    }

    // 成功后原子替换
    std::fs::rename(&tmp_path, &xipk_path).map_err(|e| {
        let _ = std::fs::remove_file(&tmp_path);
        anyhow::anyhow!("替换 xipk 失败 {}: {e}", xipk_path.display())
    })?;

    Ok(xipk_path)
}

fn add_dir(
    zip: &mut zip::ZipWriter<std::fs::File>,
    root: &Path,
    dir: &Path,
    options: SimpleFileOptions,
) -> anyhow::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            add_dir(zip, root, &path, options)?;
        } else {
            let rel = path
                .strip_prefix(root)
                .map_err(|e| anyhow::anyhow!("路径计算失败: {e}"))?
                .to_string_lossy()
                .replace('\\', "/");
            zip.start_file(rel, options)?;
            zip.write_all(&std::fs::read(&path)?)?;
        }
    }
    Ok(())
}

/// 拷贝 xipk 到 app 内置资源目录（debug 构建自动安装的内置插件）。
pub fn copy_to_assets(xipk: &Path, assets_dir: &Path) -> anyhow::Result<PathBuf> {
    std::fs::create_dir_all(assets_dir)
        .map_err(|e| anyhow::anyhow!("创建 assets 目录失败 {}: {e}", assets_dir.display()))?;
    let target = assets_dir.join(
        xipk.file_name()
            .ok_or_else(|| anyhow::anyhow!("非法 xipk 路径: {}", xipk.display()))?,
    );
    std::fs::copy(xipk, &target)?;
    Ok(target)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(tag: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "xipm-pack-{tag}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
        ))
    }

    fn manifest_with_version(version: &str) -> Manifest {
        json5::from_str(&format!(
            r#"{{ "id": "demo", "version": "{version}", "type": "tool" }}"#
        ))
        .unwrap()
    }

    /// M10：打包失败时旧包必须保留（不能被提前删除）。
    #[cfg(unix)]
    #[test]
    fn failed_pack_keeps_previous_xipk() {
        let root = temp_dir("keep-old");
        let out_dir = root.join("demo");
        std::fs::create_dir_all(&out_dir).unwrap();
        std::fs::write(out_dir.join("main.js"), "var plugin = {};").unwrap();
        std::fs::write(out_dir.join("manifest.json"), "{}").unwrap();
        let xipk_dir = root.join("release");

        // 先用正常产物打一次包
        let manifest = manifest_with_version("1.0.0");
        let xipk = pack_plugin(&out_dir, &manifest, &xipk_dir).unwrap();
        assert!(xipk.is_file());
        let old_bytes = std::fs::read(&xipk).unwrap();

        // 注入坏条目（悬空符号链接 → 读取失败），再打包
        std::os::unix::fs::symlink(root.join("missing-target"), out_dir.join("broken.txt")).unwrap();
        pack_plugin(&out_dir, &manifest, &xipk_dir).expect_err("坏条目必须导致打包失败");

        // 旧包必须原样保留
        assert!(xipk.is_file(), "打包失败后旧包被删除");
        assert_eq!(std::fs::read(&xipk).unwrap(), old_bytes, "旧包内容被破坏");
        // 不留临时文件
        let leftovers: Vec<String> = std::fs::read_dir(&xipk_dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n != "demo-1.0.0.xipk")
            .collect();
        assert!(leftovers.is_empty(), "残留临时文件: {leftovers:?}");

        std::fs::remove_dir_all(root).ok();
    }

    /// M10：同版本重新打包要原子替换为新内容。
    #[test]
    fn repack_same_version_replaces_content() {
        let root = temp_dir("repack");
        let out_dir = root.join("demo");
        std::fs::create_dir_all(&out_dir).unwrap();
        std::fs::write(out_dir.join("main.js"), "first").unwrap();
        let xipk_dir = root.join("release");
        let manifest = manifest_with_version("1.0.0");

        let xipk = pack_plugin(&out_dir, &manifest, &xipk_dir).unwrap();
        let first_len = std::fs::metadata(&xipk).unwrap().len();

        std::fs::write(out_dir.join("main.js"), "second-content-longer-than-first").unwrap();
        let xipk2 = pack_plugin(&out_dir, &manifest, &xipk_dir).unwrap();
        assert_eq!(xipk, xipk2);
        assert!(std::fs::metadata(&xipk2).unwrap().len() > first_len, "旧包未被新内容替换");

        std::fs::remove_dir_all(root).ok();
    }
}
