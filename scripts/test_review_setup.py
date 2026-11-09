#!/usr/bin/env python3
"""Real bubblewrap/filesystem tests; no application build, launch or host menu writes."""
import hashlib
import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('review_setup', Path(__file__).with_name('review_setup.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='nia-review-setup-', dir='/var/tmp')
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name) / 'home'
        self.home.mkdir()
        self.menu = self.home / '.local/share/applications'
        self.menu.mkdir(parents=True)
        (self.menu / 'nozzle-it-all.desktop').write_text('accepted launcher\n')
        (self.home / 'Desktop').mkdir()
        self.env = dict(os.environ, HOME=str(self.home), XDG_DATA_HOME=str(self.home / '.local/share'),
                        XDG_DATA_DIRS='/usr/local/share:/usr/share', XDG_CONFIG_HOME=str(self.home / '.config'))
        self.script = str(Path(__file__).with_name('review_setup.py'))
        self.python = sys.executable

    def run_setup(self, code, commit='a' * 40):
        return subprocess.run([self.python, self.script, '--candidate', 'p3-' + commit, '--',
                               self.python, '-c', code], env=self.env, capture_output=True, text=True)

    def test_repeated_candidates_cannot_register_menu_or_desktop_entries(self):
        before = (self.menu / 'nozzle-it-all.desktop').read_bytes()
        for commit in ['a' * 40, 'b' * 40, 'a' * 40]:
            code = '''import os
from pathlib import Path
p=Path(os.environ['NOZZLE_REVIEW_WORKSPACE'])
(p/'launch.sh').write_text('# candidate wrapper\\n')
for target in [Path.home()/'.local/share/applications/new-commit.desktop',
               Path.home()/'Desktop/new-commit.desktop',
               Path.home()/'.local/share/applications/nozzle-it-all.desktop']:
 try: target.write_text('bad')
 except OSError: pass
 else: raise AssertionError('menu write succeeded')
'''
            r = self.run_setup(code, commit)
            self.assertEqual(r.returncode, 0, r.stderr)
            receipt = json.loads(r.stdout)
            self.assertTrue(receipt['menu_unchanged'])
            self.assertTrue((Path(receipt['workspace']) / 'launch.sh').exists())
        self.assertEqual(list(self.menu.glob('*.desktop')), [self.menu / 'nozzle-it-all.desktop'])
        self.assertEqual((self.menu / 'nozzle-it-all.desktop').read_bytes(), before)
        self.assertEqual(len(list((self.home / '.local/state/nozzle-it-all/review-setup-receipts').glob('*.json'))), 3)

    def test_failed_setup_is_not_reported_successful(self):
        r = self.run_setup("from pathlib import Path; (Path.home()/'.local/share/applications/fail.desktop').write_text('x')")
        self.assertNotEqual(r.returncode, 0)
        self.assertNotEqual(json.loads(r.stdout)['setup_exit_code'], 0)
        self.assertFalse((self.menu / 'fail.desktop').exists())

    def test_symlink_cannot_escape_workspace(self):
        r = self.run_setup("import os; from pathlib import Path; p=Path(os.environ['NOZZLE_REVIEW_WORKSPACE'])/'escape'; p.symlink_to(Path.home()/'.local/share/applications'); (p/'escape.desktop').write_text('x')")
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse((self.menu / 'escape.desktop').exists())

    def test_missing_bubblewrap_fails_closed(self):
        with patch.object(guard, 'BWRAP', self.home / 'missing-bwrap'):
            with self.assertRaisesRegex(ValueError, 'no unsandboxed fallback'):
                guard.run('p3-' + 'a' * 40, ['/usr/bin/true'])

    def test_path_shadow_cannot_replace_bubblewrap(self):
        bin_dir = self.home / 'fake-bin'
        bin_dir.mkdir()
        shim = bin_dir / 'bwrap'
        shim.write_text('#!/bin/sh\necho bypassed > "$HOME/shim-ran"\nexit 0\n')
        shim.chmod(0o755)
        self.env['PATH'] = str(bin_dir) + os.pathsep + os.environ.get('PATH', '/usr/bin:/bin')
        r = self.run_setup("from pathlib import Path; (Path.home()/'.local/share/applications/bypass.desktop').write_text('bad')")
        self.assertNotEqual(r.returncode, 0)
        self.assertFalse((self.home / 'shim-ran').exists())
        self.assertFalse((self.menu / 'bypass.desktop').exists())

    def test_host_socket_service_cannot_register_a_launcher(self):
        runtime = self.home / 'runtime'
        runtime.mkdir()
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(server.close)
        server.bind(str(runtime / 'fixture-bus'))
        server.listen()
        server.settimeout(0.1)
        self.env['XDG_RUNTIME_DIR'] = str(runtime)
        r = self.run_setup("import os,socket; s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM); s.connect(os.environ['XDG_RUNTIME_DIR']+'/fixture-bus'); s.sendall(b'register-launcher')")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('Operation not permitted', r.stderr)
        self.assertTrue(json.loads(r.stdout)['menu_unchanged'])
        with self.assertRaises(TimeoutError): server.accept()

    def test_socketpair_datagram_cannot_reach_host_service(self):
        server = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
        self.addCleanup(server.close)
        address = self.home / 'datagram-fixture'
        server.bind(str(address))
        server.settimeout(0.1)
        r = self.run_setup(f"import socket; a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM); a.sendto(b'register-launcher',{str(address)!r})")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn('Operation not permitted', r.stderr)
        self.assertTrue(json.loads(r.stdout)['menu_unchanged'])
        with self.assertRaises(TimeoutError): server.recvfrom(100)

    def test_missing_socket_filter_dependency_fails_closed(self):
        with patch.object(guard.ctypes, 'CDLL', side_effect=OSError('missing filter dependency')):
            with self.assertRaises(OSError): guard.socket_filter()

    def test_exported_filter_denies_connect_and_io_uring(self):
        # Only this subprocess loads the actual exported filter. It opens a
        # socket first to test connect independently of the socket denial.
        code = r"""import ctypes,errno,importlib.util,os,socket,struct,sys
spec=importlib.util.spec_from_file_location('guard',sys.argv[1]);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
lib=ctypes.CDLL('libseccomp.so.2');lib.seccomp_syscall_resolve_name.argtypes=[ctypes.c_char_p];lib.seccomp_syscall_resolve_name.restype=ctypes.c_int
fd=g.socket_filter();bpf=os.read(fd,100000);os.close(fd)
numbers=[lib.seccomp_syscall_resolve_name(n) for n in g.DENIED_SYSCALLS]
assert all(n>=0 and n in [x[3] for x in struct.iter_unpack('HBBI',bpf)] for n in numbers)
s=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
class F(ctypes.Structure):_fields_=[('code',ctypes.c_ushort),('jt',ctypes.c_ubyte),('jf',ctypes.c_ubyte),('k',ctypes.c_uint)]
class P(ctypes.Structure):_fields_=[('len',ctypes.c_ushort),('filter',ctypes.POINTER(F))]
a=(F*(len(bpf)//8)).from_buffer_copy(bpf);p=P(len(a),a)
c=ctypes.CDLL(None,use_errno=True);c.prctl.argtypes=[ctypes.c_int,ctypes.c_ulong,ctypes.c_void_p,ctypes.c_ulong,ctypes.c_ulong]
assert c.prctl(38,1,None,0,0)==0
assert c.prctl(22,2,ctypes.byref(p),0,0)==0
try:s.connect(sys.argv[2])
except PermissionError:pass
else:raise AssertionError('connect allowed')
for n in numbers[2:]:
 ctypes.set_errno(0)
 assert c.syscall(n,-1,0,0,0,0,0)==-1 and ctypes.get_errno()==errno.EPERM
"""
        r = subprocess.run([self.python, '-c', code, self.script, str(self.home / 'nonexistent-fixture')], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)

    def test_menu_change_status_takes_priority_over_command_failure(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(guard, 'snapshot', side_effect=[{}, {'fixture': 'changed'}]), patch.object(guard.subprocess, 'run', return_value=subprocess.CompletedProcess([], 7)), contextlib.redirect_stdout(io.StringIO()) as output:
            status = guard.run('p3-' + 'a'*40, ['/usr/bin/true'])
        self.assertEqual(status, 4)
        receipt = json.loads(output.getvalue())
        self.assertEqual(receipt['setup_exit_code'], 7)
        self.assertFalse(receipt['menu_unchanged'])

    def test_post_execution_receipt_failure_is_not_reported_refused(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(sys, 'argv', [self.script, '--candidate', 'p3-'+'a'*40, '--', '/usr/bin/true']), patch.object(guard.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)), patch.object(guard.json, 'dump', side_effect=OSError('test receipt failure')), contextlib.redirect_stderr(io.StringIO()) as output:
            status = guard.main()
        self.assertEqual(status, 3)
        self.assertIn('command ran', output.getvalue())
        self.assertNotIn('refused', output.getvalue())

    def test_command_output_cannot_corrupt_receipt_stdout(self):
        r = self.run_setup("print('setup command output')")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('setup command output', r.stderr)
        receipt = json.loads(r.stdout)
        self.assertEqual(receipt['setup_exit_code'], 0)
        self.assertTrue(receipt['menu_unchanged'])

    def test_unexpected_guard_failure_is_not_command_failure(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(sys, 'argv', [self.script, '--candidate', 'p3-'+'a'*40, '--', '/usr/bin/true']), patch.object(guard, 'socket_filter', side_effect=AttributeError('fixture API unavailable')), patch.object(guard.subprocess, 'run') as command, contextlib.redirect_stderr(io.StringIO()) as output:
            status = guard.main()
        self.assertEqual(status, 5)
        command.assert_not_called()
        self.assertIn('execution status unknown', output.getvalue())
        self.assertFalse((self.home / '.local/state/nozzle-it-all').exists())

    def test_missing_filter_entrypoint_refuses_without_state_writes(self):
        with patch.dict(os.environ, self.env, clear=True), patch.object(sys, 'argv', [self.script, '--candidate', 'p3-'+'a'*40, '--', '/usr/bin/true']), patch.object(guard.ctypes, 'CDLL', side_effect=OSError('fixture unavailable filter')), patch.object(guard.subprocess, 'run') as command, contextlib.redirect_stderr(io.StringIO()):
            status = guard.main()
        self.assertEqual(status, 2)
        command.assert_not_called()
        self.assertFalse((self.home / '.local/state/nozzle-it-all').exists())

    def test_unparsed_desktop_assignment_is_refused(self):
        config = self.home / '.config'
        config.mkdir()
        for line in ["XDG_DESKTOP_DIR='$HOME/custom'", 'export XDG_DESKTOP_DIR="$HOME/custom"']:
            (config / 'user-dirs.dirs').write_text(line + '\n')
            r = self.run_setup("raise AssertionError('must not run')")
            self.assertEqual(r.returncode, 2)
            self.assertIn('unsupported XDG_DESKTOP_DIR syntax', r.stderr)

    def test_relative_xdg_directories_refused_before_command(self):
        for key in ['XDG_DATA_HOME', 'XDG_DATA_DIRS', 'XDG_CONFIG_HOME']:
            env = self.env.copy()
            self.env[key] = 'relative-path'
            r = self.run_setup("raise AssertionError('must not run')")
            self.assertEqual(r.returncode, 2)
            self.assertIn('must be absolute', r.stderr)
            self.env = env
        self.assertFalse((self.home / '.local/state/nozzle-it-all/review-builds').exists())

    def test_invalid_candidate_and_empty_command_refused(self):
        for c, cmd in [('p3-abc', ['/usr/bin/true']), ('../escape', ['/usr/bin/true']), ('p3-' + 'a'*40, [])]:
            with self.assertRaises(ValueError): guard.run(c, cmd)

    def test_redirected_state_directory_refused_before_write(self):
        state = self.home / '.local/state'
        state.symlink_to(self.home / '.local/share')
        r = self.run_setup('raise AssertionError("must not run")')
        self.assertEqual(r.returncode, 2)
        self.assertFalse((self.home / '.local/share/nozzle-it-all').exists())

    def test_custom_xdg_menu_and_custom_desktop_protected(self):
        custom = self.home / 'custom-data'
        (custom / 'applications').mkdir(parents=True)
        desktop = self.home / 'custom-desktop'; desktop.mkdir()
        cfg = self.home / '.config'; cfg.mkdir()
        (cfg / 'user-dirs.dirs').write_text(f'XDG_DESKTOP_DIR="{desktop}"\n')
        self.env['XDG_DATA_HOME'] = str(custom)
        code = f"from pathlib import Path; Path({str(custom / 'applications/new.desktop')!r}).write_text('x')"
        self.assertNotEqual(self.run_setup(code).returncode, 0)
        self.assertFalse((custom / 'applications/new.desktop').exists())
        self.assertNotEqual(self.run_setup(f"from pathlib import Path; Path({str(desktop / 'new.desktop')!r}).write_text('x')").returncode, 0)

    def test_nested_entries_included_in_snapshot_and_write_protection(self):
        nested = self.menu / 'nested'
        nested.mkdir()
        entry = nested / 'review.desktop'
        entry.write_text('original')
        roots = guard.menu_roots(self.home, self.env)
        before = guard.snapshot(roots)
        self.assertIn(str(entry), before)
        entry.write_text('changed outside sandbox')
        self.assertNotEqual(before, guard.snapshot(roots))
        blocked = self.run_setup(f"from pathlib import Path; Path({str(entry)!r}).write_text('bad')")
        self.assertNotEqual(blocked.returncode, 0)
        self.assertEqual(entry.read_text(), 'changed outside sandbox')

    def test_receipts_and_unlisted_host_paths_are_read_only(self):
        r = self.run_setup("""from pathlib import Path
for target in [Path.home()/'.local/state/nozzle-it-all/review-setup-receipts/tamper.json',
               Path.home()/'.config/applications/new.desktop',Path.home()/'applications/new.desktop']:
 try:
  target.parent.mkdir(parents=True,exist_ok=True)
  target.write_text('tamper')
 except OSError:pass
 else:raise AssertionError('host write succeeded')
""")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(json.loads(r.stdout)['menu_unchanged'])
        self.assertFalse((self.home / '.local/state/nozzle-it-all/review-setup-receipts/tamper.json').exists())

    def test_private_tmp_does_not_write_host_tmp(self):
        name = 'nia-guard-' + hashlib.sha256(str(self.home).encode()).hexdigest() + '.desktop'
        r = self.run_setup(f"from pathlib import Path; Path('/tmp/{name}').write_text('private')")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertFalse((Path('/tmp') / name).exists())


if __name__ == '__main__': unittest.main()
