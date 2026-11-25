"""Install Xime without uninstalling or discarding application data."""
import argparse
from pathlib import Path
import subprocess
import sys
import zipfile
import os
import re
import shutil

PACKAGE = "com.kingzcheung.xime"
ROOT = Path(__file__).resolve().parent.parent


def install_hint(output):
    hints = {
        'INSTALL_FAILED_UPDATE_INCOMPATIBLE': '签名与已安装版本不一致，请使用原签名重新构建；不要卸载原应用。',
        'INSTALL_FAILED_VERSION_DOWNGRADE': '安装包版本号低于已安装版本，请构建正确的升级版本。',
        'INSTALL_FAILED_INSUFFICIENT_STORAGE': '设备存储空间不足，请释放空间后重试。',
        'INSTALL_FAILED_NO_MATCHING_ABIS': '安装包原生架构与设备不兼容，请选择匹配的 APK。',
        'INSTALL_PARSE_FAILED_NO_CERTIFICATES': '安装包未签名或签名无效，请重新签名并验签。',
    }
    return next((hint for code, hint in hints.items() if code in output), '')


def execute(command, runner):
    result = runner(command, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
    if result.returncode:
        output = f"{result.stdout.strip()} {result.stderr.strip()}"
        raise RuntimeError(f"命令失败 ({result.returncode}): {output} {install_hint(output)}")
    return result.stdout


def devices(text):
    found = {}
    for line in text.splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] != "List" and not line.startswith("*"):
            found[fields[0]] = fields[1]
    return found


def select_device(text, serial=None):
    entries = devices(text)
    if serial:
        if entries.get(serial) != "device":
            raise ValueError("指定设备不存在或未授权/离线")
        return serial
    ready = [key for key, state in entries.items() if state == "device"]
    if len(ready) != 1:
        raise ValueError("需要唯一已授权设备；多设备时请指定 --serial")
    return ready[0]


def apk_abis(path):
    with zipfile.ZipFile(path) as archive:
        return {name.split('/')[1] for name in archive.namelist()
                if name.startswith('lib/') and len(name.split('/')) >= 3 and name.endswith('.so')}


def validate_package(path, tool, runner):
    if not tool:
        tool = shutil.which('aapt2')
        if not tool:
            sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
            tools = sorted((Path(sdk) / 'build-tools').glob('*/aapt2*')) if sdk else []
            tool = str(next((p for p in reversed(tools) if p.name in ('aapt2', 'aapt2.exe') and p.is_file()), ''))
    if not tool:
        raise ValueError('需要 Android SDK aapt2 校验包名，请指定 --aapt2')
    output = execute([str(tool), 'dump', 'badging', str(path)], runner)
    match = re.search(r"^package: name='([^']+)'", output, re.MULTILINE)
    if not match or match.group(1) != PACKAGE:
        raise ValueError('APK 包名不是 Xime，拒绝安装')


def select_apk(candidates, supported):
    ranked = []
    for path in candidates:
        abis = apk_abis(path)
        matches = [supported.index(abi) for abi in abis if abi in supported]
        if abis and not matches:
            continue
        ranked.append((min(matches) if matches else len(supported), path))
    if not ranked:
        raise ValueError("没有兼容设备 ABI 的 APK")
    best = min(rank for rank, _ in ranked)
    choices = [path for rank, path in ranked if rank == best]
    if len(choices) != 1:
        raise ValueError("发现多个同等匹配 APK，请用 --apk 明确选择")
    return choices[0]


def main(argv=None, runner=subprocess.run, root=ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--action', choices=['all', 'app', 'plugins', 'uninstall'], default='all')
    parser.add_argument('--source', choices=['ide', 'build'], default='ide')
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--serial')
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--aapt2')
    parser.add_argument('--confirm-uninstall', action='store_true')
    args = parser.parse_args(argv)
    try:
        if args.action == 'plugins':
            raise ValueError("APK 插件已废弃，请使用 xipm 安装 .xipk")
        if args.action == 'uninstall' and not args.confirm_uninstall:
            raise ValueError("卸载会删除应用数据，必须显式传入 --confirm-uninstall")
        candidates = []
        if args.action != 'uninstall':
            if args.apk:
                candidates = [args.apk.expanduser().resolve()]
            else:
                folder = root / ('app/release' if args.source == 'ide' else 'app/build/outputs/apk/release')
                candidates = sorted(folder.glob('*.apk'))
            if not candidates or any(not path.is_file() or path.suffix.lower() != '.apk' for path in candidates):
                raise ValueError("APK 不存在，请先构建或用 --apk 指定文件")
            # Reject malformed archives before contacting the device.
            for path in candidates:
                apk_abis(path)
        serial = select_device(execute([args.adb, 'devices'], runner), args.serial)
        adb = [args.adb, '-s', serial]
        if args.action == 'uninstall':
            output = execute(adb + ['uninstall', PACKAGE], runner)
        else:
            supported = execute(adb + ['shell', 'getprop', 'ro.product.cpu.abilist'], runner).strip().split(',')
            supported = [abi.strip() for abi in supported if abi.strip()]
            if not supported:
                raise ValueError("无法读取设备 ABI，拒绝猜测安装包")
            apk = select_apk(candidates, supported)
            validate_package(apk, args.aapt2, runner)
            print(f"设备: {serial}; APK: {apk}")
            output = execute(adb + ['install', '-r', str(apk)], runner)
        if 'Success' not in [line.strip() for line in output.splitlines()]:
            raise RuntimeError(f"ADB 未确认成功: {output.strip()} {install_hint(output)}")
        print('Success')
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"错误: {error}", file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
