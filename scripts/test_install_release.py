import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('installer', Path(__file__).with_name('install_release.py'))
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


class InstallTest(unittest.TestCase):
    def setUp(self):
        tool = patch.object(installer.shutil, 'which', return_value='aapt2')
        tool.start()
        self.addCleanup(tool.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.folder = self.root / 'app/release'
        self.folder.mkdir(parents=True)

    def apk(self, name='Xime-arm64.apk', abi='arm64-v8a'):
        path = self.folder / name
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr(f'lib/{abi}/libtest.so', b'fixture')
        return path

    def runner(self, install='Success\n', code=0, device='phone\tdevice\n'):
        def run(command, **kwargs):
            if 'badging' in command: return subprocess.CompletedProcess(command, 0, "package: name='com.kingzcheung.xime' versionCode='1'\n", '')
            if command[-1] == 'devices': return subprocess.CompletedProcess(command, 0, 'List of devices attached\n'+device, '')
            if 'getprop' in command: return subprocess.CompletedProcess(command, 0, 'arm64-v8a,armeabi-v7a\n', '')
            return subprocess.CompletedProcess(command, code, install, '')
        return Mock(side_effect=run)

    def test_default_preserves_data(self):
        path = self.apk()
        run = self.runner()
        self.assertEqual(0, installer.main([], run, self.root))
        commands = [c.args[0] for c in run.call_args_list]
        self.assertEqual(['adb', '-s', 'phone', 'install', '-r', str(path)], commands[-1])
        self.assertFalse(any('uninstall' in c for c in commands))

    def test_missing_apk_fails_before_adb(self):
        run = self.runner()
        self.assertEqual(1, installer.main([], run, self.root))
        run.assert_not_called()

    def test_explicit_apk(self):
        path = self.apk()
        self.assertEqual(0, installer.main(['--apk', str(path)], self.runner(), self.root / 'elsewhere'))

    def test_ambiguous_apk_refused(self):
        self.apk(); self.apk('second.apk')
        run = self.runner()
        self.assertEqual(1, installer.main([], run, self.root))
        self.assertFalse(any('install' in c.args[0] for c in run.call_args_list))

    def test_incompatible_abi_refused(self):
        self.apk(abi='x86')
        self.assertEqual(1, installer.main([], self.runner(), self.root))

    def test_select_abi_preference(self):
        slow = self.apk('arm.apk', 'armeabi-v7a'); best = self.apk()
        self.assertEqual(best, installer.select_apk([slow,best], ['arm64-v8a','armeabi-v7a']))

    def test_devices_exact_states(self):
        for listing in ['List of devices attached\n', 'a\tunauthorized\n', 'a\toffline\n', 'a\tdevice\nb\tdevice\n']:
            with self.subTest(listing=listing), self.assertRaises(ValueError): installer.select_device(listing)
        self.assertEqual('b', installer.select_device('a\tdevice\nb\tdevice\n','b'))
        with self.assertRaises(ValueError): installer.select_device('b\tunauthorized','b')

    def test_failure_never_uninstalls(self):
        self.apk()
        for code, output in [(1,'Failure'),(0,'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'),(0,'Not Success')]:
            run = self.runner(output, code)
            self.assertEqual(1, installer.main([], run, self.root))
            self.assertFalse(any('uninstall' in c.args[0] for c in run.call_args_list))

    def test_uninstall_requires_confirmation(self):
        run = self.runner()
        self.assertEqual(1, installer.main(['--action','uninstall'],run,self.root))
        run.assert_not_called()
        self.assertEqual(0, installer.main(['--action','uninstall','--confirm-uninstall'],run,self.root))
        self.assertEqual(['adb','-s','phone','uninstall',installer.PACKAGE],run.call_args.args[0])

    def test_plugins_rejected(self):
        run = self.runner()
        self.assertEqual(1,installer.main(['--action','plugins'],run,self.root))
        run.assert_not_called()

    def test_adb_errors_propagate(self):
        self.apk()
        for failure in [FileNotFoundError('adb missing'),subprocess.TimeoutExpired('adb',180)]:
            self.assertEqual(1,installer.main([],Mock(side_effect=failure),self.root))
        run=Mock(return_value=subprocess.CompletedProcess([],1,'','devices failed'))
        self.assertEqual(1,installer.main([],run,self.root))

    def test_failure_hints_preserve_data(self):
        self.assertIn('不要卸载', installer.install_hint('INSTALL_FAILED_UPDATE_INCOMPATIBLE'))
        self.assertIn('版本号', installer.install_hint('INSTALL_FAILED_VERSION_DOWNGRADE'))
        self.assertIn('存储', installer.install_hint('INSTALL_FAILED_INSUFFICIENT_STORAGE'))
        self.assertIn('架构', installer.install_hint('INSTALL_FAILED_NO_MATCHING_ABIS'))
        self.assertIn('签名', installer.install_hint('INSTALL_PARSE_FAILED_NO_CERTIFICATES'))
        self.assertEqual('', installer.install_hint('unknown error'))

    def test_wrong_package_refused(self):
        self.apk()
        normal = self.runner().side_effect
        def run(command, **kwargs):
            if 'badging' in command:
                return subprocess.CompletedProcess(command, 0, "package: name='other.app'\n", '')
            return normal(command, **kwargs)
        runner = Mock(side_effect=run)
        self.assertEqual(1, installer.main([], runner, self.root))
        self.assertFalse(any('install' in c.args[0] for c in runner.call_args_list))

    def test_package_probe_failure(self):
        for result in [subprocess.CompletedProcess([], 1, '', 'bad apk'),
                       subprocess.CompletedProcess([], 0, 'no metadata', '')]:
            with self.subTest(result=result), self.assertRaises((RuntimeError, ValueError)):
                installer.validate_package(Path('x.apk'), 'aapt2', Mock(return_value=result))

    def test_missing_package_tool_refused(self):
        with patch.object(installer.shutil, 'which', return_value=None), patch.dict(installer.os.environ, {}, clear=True):
            with self.assertRaises(ValueError):
                installer.validate_package(Path('x.apk'), None, Mock())

    def test_bad_archive_rejected_before_adb(self):
        (self.folder/'bad.apk').write_text('not zip')
        run=self.runner()
        self.assertEqual(1,installer.main([],run,self.root))
        run.assert_not_called()


if __name__ == '__main__': unittest.main()
