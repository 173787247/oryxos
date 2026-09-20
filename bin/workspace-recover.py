#!/usr/bin/env python3
"""Offline POSIX workspace recovery. Defaults to inspection; never takes over a live writer.

An ownerless reservation is reported with OWNERLESS-RESERVATION. Applying recovery requires
supplying that sentinel as --token plus --confirm-writers-stopped; it is never reclaimed online.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import stat
import uuid

OWNERLESS_ACK = 'OWNERLESS-RESERVATION'


def regular(path):
    if not stat.S_ISREG(path.lstat().st_mode):
        raise ValueError(f"Not a regular file: {path}")


def atomic_copy(source, target):
    temporary = target.with_name(f".workspace-recover-{uuid.uuid4()}")
    try:
        shutil.copyfile(source, temporary)
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def recover(root, apply=False, token=None, identity=None, confirm_writers_stopped=False):
    root = root.resolve(strict=True)
    marker = root / '.workspace-id'
    marker_present = marker.exists() or marker.is_symlink()
    if identity is not None and not marker_present:
        raise ValueError('Expected shared workspace identity marker is missing')
    if marker_present:
        regular(marker)
        if identity is None or marker.read_text().strip() != identity:
            raise ValueError('Supply the matching --identity for this shared workspace')
    if apply and not confirm_writers_stopped:
        raise ValueError('All writers must be stopped before --apply')
    lock = root / '.workspace-write'
    if lock.exists() or lock.is_symlink():
        if lock.is_symlink() or not lock.is_dir():
            raise ValueError('Invalid publication reservation')
        owner = lock / 'owner'
        if owner.exists() or owner.is_symlink():
            regular(owner)
            actual = owner.read_text().strip()
            ownerless = False
            if apply and token != actual:
                raise ValueError('Supply the inspected --token; all writers must be stopped')
        else:
            # A crash can occur before owner publication or after owner deletion. The sentinel is
            # deliberately explicit and is accepted only together with offline-stop consent.
            actual = OWNERLESS_ACK
            ownerless = True
            if apply and token != OWNERLESS_ACK:
                raise ValueError(
                    f'Supply --token {OWNERLESS_ACK} as explicit ownerless acknowledgement')
    else:
        actual = None
        ownerless = False
    transactions = []
    actions = []
    for journal in sorted((root / 'agents').glob('*/.workspace-transaction')):
        if journal.is_symlink() or not journal.is_dir():
            raise ValueError(f'Invalid journal: {journal}')
        agent = journal.parent.resolve(strict=True)
        if not agent.is_relative_to(root / 'agents'):
            raise ValueError('Escaped agent directory')
        committed = (journal / 'committed').exists()
        manifest = journal / 'manifest'
        if not committed and manifest.exists():
            regular(manifest)
            for line in manifest.read_text().splitlines():
                index, existed, encoded = line.split('\t')
                if not index.isdecimal() or existed not in ('true', 'false'):
                    raise ValueError('Invalid manifest')
                relative = Path(base64.b64decode(encoded, validate=True).decode('utf-8'))
                target = (agent / relative).resolve()
                if relative.is_absolute() or target == agent or not target.is_relative_to(agent):
                    raise ValueError('Escaped transaction target')
                if any(part.startswith('.workspace-') for part in relative.parts):
                    raise ValueError('Reserved transaction target')
                backup = journal / f'{index}.old'
                if existed == 'true':
                    regular(backup)
                actions.append((target, backup if existed == 'true' else None))
        transactions.append(journal)
    result = {'reservation': actual, 'ownerless': ownerless, 'journals': len(transactions),
              'restoreFiles': len(actions), 'applied': apply}
    if apply:
        # Validate every journal before the first mutation. Backups stay intact for repeatable recovery.
        for target, backup in actions:
            if backup is None:
                target.unlink(missing_ok=True)
            else:
                atomic_copy(backup, target)
        for journal in transactions:
            journal.rename(journal.with_name(f'.workspace-recovered-{uuid.uuid4()}'))
        revision = root / '.workspace-revision'
        temporary = root / f'.workspace-revision-{uuid.uuid4()}'
        temporary.write_text(str(uuid.uuid4()) + '\n')
        os.replace(temporary, revision)
        if lock.exists():
            lock.rename(root / f'.workspace-recovered-reservation-{uuid.uuid4()}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument(
        '--identity',
        help='required expected marker value for shared workspaces; a missing marker is fatal',
    )
    parser.add_argument(
        '--token',
        help=f'inspected owner token, or {OWNERLESS_ACK} for an ownerless reservation',
    )
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--confirm-writers-stopped', action='store_true')
    args = parser.parse_args()
    if args.apply and not args.confirm_writers_stopped:
        parser.error('--apply requires --confirm-writers-stopped')
    print(json.dumps(recover(
        args.root,
        args.apply,
        args.token,
        args.identity,
        args.confirm_writers_stopped,
    ), indent=2))


if __name__ == '__main__':
    main()
