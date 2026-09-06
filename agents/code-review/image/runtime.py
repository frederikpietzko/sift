"""Assemble a scratch runtime from explicit binaries plus their ELF dependencies (Linux builder only)."""
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path('/rootfs')
BINARIES = ('/usr/bin/git', '/usr/lib/git-core/git-remote-http', '/usr/bin/bash',
            '/usr/bin/rg', '/usr/bin/env', '/usr/bin/cat', '/usr/bin/sleep', '/usr/bin/rm')


def copy(path):
    destination = ROOT / str(path).lstrip('/')
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, destination, follow_symlinks=True)


def dependencies(path):
    output = subprocess.run(['ldd', str(path)], capture_output=True, text=True, check=False).stdout
    for library in re.findall(r'(/[^\s()]+)', output):
        if Path(library).is_file():
            copy(library)


def main():
    shutil.copytree('/opt/java/openjdk', ROOT / 'opt/java/openjdk')
    for path in (ROOT / 'opt/java/openjdk/bin').iterdir():
        if path.name != 'java':
            path.unlink()
    for path in Path('/opt/java/openjdk').rglob('*'):
        if path.is_file() and ('.so' in path.name or path.parent.name == 'bin'):
            dependencies(path)
    for path in BINARIES:
        copy(path)
        dependencies(path)
    for alias, target in [('/bin/sh', '/usr/bin/bash'), ('/bin/bash', '/usr/bin/bash'),
                          ('/usr/lib/git-core/git', '/usr/bin/git'),
                          ('/usr/lib/git-core/git-remote-https', 'git-remote-http')]:
        path = ROOT / alias.lstrip('/')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.symlink_to(target)
    shutil.copytree('/usr/share/git-core/templates', ROOT / 'usr/share/git-core/templates')
    # Hooks are samples, not needed for checkout or review.
    shutil.rmtree(ROOT / 'usr/share/git-core/templates/hooks')
    copy('/etc/ssl/certs/ca-certificates.crt')
    copy('/etc/os-release')
    copy('/etc/lsb-release')
    copy('/etc/debian_version')
    (ROOT / 'etc/passwd').write_text('sift:x:10001:10001:Sift review:/scratch:/bin/bash\n')
    (ROOT / 'etc/group').write_text('sift:x:10001:\n')
    for name in ('scratch', 'tmp'):
        path = ROOT / name
        path.mkdir(mode=0o1777)
    packages = subprocess.check_output(['dpkg-query', '-W', '-f=${Package}=${Version}\n'], text=True)
    (ROOT / 'etc/runtime-build-packages.txt').write_text(packages)
    # Keep package metadata for the libraries/binaries actually copied, so scanners can identify them.
    selected = {'ca-certificates'}
    for path in ROOT.rglob('*'):
        if path.is_file() and not path.is_symlink():
            original = '/' + str(path.relative_to(ROOT))
            owners = subprocess.run(['dpkg-query', '-S', str(Path(original).resolve())],
                                    text=True, capture_output=True).stdout
            for owner in owners.splitlines():
                selected.add(owner.split(': ')[0].split(':')[0])
    status = ROOT / 'var/lib/dpkg/status'
    status.parent.mkdir(parents=True)
    status.write_text('\n\n'.join(record for record in Path('/var/lib/dpkg/status').read_text().split('\n\n')
                                  if record.splitlines() and record.splitlines()[0].removeprefix('Package: ') in selected))
    shutil.copytree('/usr/share/common-licenses', ROOT / 'usr/share/common-licenses')
    for package in selected:
        copyright_file = Path('/usr/share/doc') / package / 'copyright'
        if copyright_file.is_file():
            copy(copyright_file)


if __name__ == '__main__':
    main()