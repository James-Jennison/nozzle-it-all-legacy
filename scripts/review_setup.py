#!/usr/bin/env python3
"""Run local review-candidate setup without write access to the host app menu.

Usage: python3 scripts/review_setup.py --candidate p3-FULL_COMMIT -- COMMAND...
The command runs in NOZZLE_REVIEW_WORKSPACE, the sole writable host directory.
This guards setup commands, not builds, application launches, or hostile programs.
"""
from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import stat
import sys
import uuid


BWRAP = Path('/usr/bin/bwrap')
DENIED_SYSCALLS = (b'socket', b'connect', b'io_uring_setup', b'io_uring_enter', b'io_uring_register', b'socketpair', b'sendto', b'sendmsg', b'sendmmsg')


class PostSetupError(RuntimeError):
    pass


def socket_filter() -> int:
    """Export an architecture-aware seccomp filter; no host-service socket IPC."""
    lib = ctypes.CDLL('libseccomp.so.2', use_errno=True)
    lib.seccomp_init.argtypes = [ctypes.c_uint32]
    lib.seccomp_init.restype = ctypes.c_void_p
    lib.seccomp_release.argtypes = [ctypes.c_void_p]
    lib.seccomp_release.restype = None
    lib.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
    lib.seccomp_syscall_resolve_name.restype = ctypes.c_int
    lib.seccomp_rule_add.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint]
    lib.seccomp_rule_add.restype = ctypes.c_int
    lib.seccomp_export_bpf.argtypes = [ctypes.c_void_p, ctypes.c_int]
    lib.seccomp_export_bpf.restype = ctypes.c_int
    ctx = lib.seccomp_init(0x7fff0000)  # SCMP_ACT_ALLOW by default
    if not ctx:
        raise ValueError('cannot initialize mandatory setup socket filter')
    fd = None
    try:
        for name in DENIED_SYSCALLS:
            syscall = lib.seccomp_syscall_resolve_name(name)
            if syscall < 0 or lib.seccomp_rule_add(ctx, 0x50000 | errno.EPERM, syscall, 0) != 0:
                raise ValueError('cannot build mandatory setup socket filter')
        fd = os.memfd_create('nozzle-review-setup-filter', os.MFD_CLOEXEC)
        if lib.seccomp_export_bpf(ctx, fd) != 0:
            raise ValueError('cannot export mandatory setup socket filter')
        os.lseek(fd, 0, os.SEEK_SET)
        return fd
    except BaseException:
        if fd is not None:
            os.close(fd)
        raise
    finally:
        lib.seccomp_release(ctx)


def menu_roots(home: Path, env: dict[str, str]) -> list[Path]:
    data = [home / '.local/share', Path(env.get('XDG_DATA_HOME') or home / '.local/share')]
    data += [Path(p) for p in (env.get('XDG_DATA_DIRS') or '/usr/local/share:/usr/share').split(':') if p]
    desktops = [home / 'Desktop']
    # Parse only the freedesktop Desktop assignment; never source shell settings.
    config = Path(env.get('XDG_CONFIG_HOME') or home / '.config') / 'user-dirs.dirs'
    if any(not p.is_absolute() for p in data + [config]):
        raise ValueError('XDG data/config directory values must be absolute')
    if config.is_file():
        for line in config.read_text().splitlines():
            stripped = line.strip()
            match = re.fullmatch(r'XDG_DESKTOP_DIR="([^"\n]*)"', stripped)
            if not match and re.match(r'(?:export\s+)?XDG_DESKTOP_DIR\s*=', stripped):
                raise ValueError('unsupported XDG_DESKTOP_DIR syntax; expected a double-quoted assignment')
            if match:
                desktops.append(Path(match[1].replace('${HOME}', str(home)).replace('$HOME', str(home))))
    if any(not p.is_absolute() for p in desktops):
        raise ValueError('configured Desktop directory must be absolute')
    return sorted(set(p.resolve() for p in [d / 'applications' for d in data] + desktops))


def overlap(a: Path, b: Path) -> bool:
    return a == b or a in b.parents or b in a.parents


def snapshot(roots: list[Path]) -> dict[str, str]:
    entries = {}
    for root in roots:
        # Include nested desktop IDs without traversing unrelated data trees.
        if root.is_dir():
            for p in root.rglob('*.desktop'):
                if p.is_file():
                    entries[str(p)] = hashlib.sha256(p.read_bytes()).hexdigest()
    return entries


def run(candidate: str, command: list[str]) -> int:
    if not re.fullmatch(r'p[0-9]+-[0-9a-f]{40}', candidate):
        raise ValueError('candidate must be pTRACK-FULL_40_CHARACTER_COMMIT')
    if not command:
        raise ValueError('an explicit setup command is required after --')
    if not BWRAP.exists():
        raise ValueError('bubblewrap is required; setup was not run (no unsandboxed fallback)')
    info = BWRAP.lstat()
    system_dir = Path('/usr/bin').stat()
    # Compare system ownership in the current user namespace: host root can
    # appear as an unmapped UID inside the evidence runner's user namespace.
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != system_dir.st_uid
            or info.st_mode & 0o022 or system_dir.st_mode & 0o022):
        raise ValueError('/usr/bin/bwrap must be a system-owned, non-writable regular file')
    home = Path.home()
    if not home.is_absolute():
        raise ValueError('HOME must be absolute')
    base = home / '.local/state/nozzle-it-all/review-builds'
    receipts = home / '.local/state/nozzle-it-all/review-setup-receipts'
    workspace = base / candidate
    roots = menu_roots(home, dict(os.environ))
    # Resolve before mkdir/bind; reject redirected state roots and menu overlap.
    for target in [base, receipts, workspace]:
        if target.resolve() != target or any(overlap(target, p) for p in roots):
            raise ValueError('review workspace/receipts must be real state directories outside menu/data roots')
    with os.fdopen(socket_filter(), 'rb') as filter_file:
        before = snapshot(roots)
        workspace.mkdir(parents=True, exist_ok=True)
        receipts.mkdir(parents=True, exist_ok=True)
        receipt = receipts / f'{candidate}-{uuid.uuid4().hex}.json'
        env = dict(os.environ, NOZZLE_REVIEW_WORKSPACE=str(workspace))
        # The host is read-only. Only this candidate tree is writable; /tmp and
        # /proc are private. No host menu directory, profile or socket is rebound RW.
        args = [str(BWRAP), '--die-with-parent', '--new-session', '--unshare-all',
                '--ro-bind', '/', '/', '--bind', str(workspace), str(workspace),
                '--tmpfs', '/tmp', '--proc', '/proc', '--dev', '/dev',
                '--chdir', str(workspace), '--unsetenv', 'DBUS_SESSION_BUS_ADDRESS',
                '--unsetenv', 'SESSION_MANAGER', '--', *command]
        args[1:1] = ['--seccomp', str(filter_file.fileno())]
        result = subprocess.run(args, env=env, pass_fds=(filter_file.fileno(),), stdout=sys.stderr, check=False)
    try:
        after = snapshot(roots)
        unchanged = before == after
        evidence = {'candidate': candidate, 'workspace': str(workspace),
                    'setup_exit_code': result.returncode, 'menu_unchanged': unchanged,
                    'menu_roots': [str(p) for p in roots],
                    'entry_count_before': len(before), 'entry_count_after': len(after),
                    'menu_snapshot_before_sha256': hashlib.sha256(json.dumps(before, sort_keys=True).encode()).hexdigest(),
                    'menu_snapshot_after_sha256': hashlib.sha256(json.dumps(after, sort_keys=True).encode()).hexdigest(),
                    'guard_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    'socket_ipc': 'socket creation, connection, datagram send and io_uring denied',
                    'denied_syscalls': [name.decode('ascii') for name in DENIED_SYSCALLS],
                    'scope': 'Setup writes confined to candidate tree; not an artifact/source or release attestation'}
        with receipt.open('x') as f:
            json.dump(evidence, f, indent=2)
            f.write('\n')
        print(json.dumps({'receipt': str(receipt), **evidence}), flush=True)
        return 4 if not unchanged else (0 if result.returncode == 0 else 1)
    except OSError as e:
        raise PostSetupError(f'setup command ran; evidence recording failed: {e}') from e


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', required=True)
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    try:
        return run(args.candidate, command)
    except PostSetupError as e:
        print(f'Review setup evidence failure: {e}', file=sys.stderr)
        return 3
    except (OSError, ValueError) as e:
        print(f'Review setup refused: {e}', file=sys.stderr)
        return 2
    except Exception as e:
        print(f'Review setup guard failed ({type(e).__name__}); command execution status unknown', file=sys.stderr)
        return 5


if __name__ == '__main__':
    sys.exit(main())
