#!/usr/bin/env python3
"""Two SSH hosts: isolated shared-volume preflight and close-to-open probe.

This verifies a storage subset, not OryxOS HA or completion of T013.
Requires Python 3/findmnt on both hosts and a dedicated opted-in test directory.
"""

import argparse
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import shlex
import subprocess
import sys
import uuid


REMOTE = r'''
import hashlib,json,os,pathlib,platform,subprocess,sys
a=json.loads(sys.argv[1])
root=pathlib.Path(a["root"])
if not root.is_absolute(): raise RuntimeError("absolute test root required")
if not (root/".042-acceptance-allowed").is_file():
    raise RuntimeError("dedicated test directory must contain .042-acceptance-allowed")
probe=root/a["token"]
action=a["action"]
if action=="info":
    mount=json.loads(subprocess.check_output(
        ["findmnt","--json","--target",str(root),"--output","TARGET,SOURCE,FSTYPE,OPTIONS"],text=True))
    result={"hostname":platform.node(),"kernel":platform.release(),
        "boot_id":pathlib.Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
        "mount":mount}
elif action=="init":
    probe.mkdir()
    result={"created":True}
elif action=="publish":
    if probe.is_symlink(): raise RuntimeError("probe must not be a symlink")
    content=(a["content"]+"\n").encode()
    temporary=probe/("stage-"+a["writer"])
    with temporary.open("xb") as f:
        f.write(content)
        f.flush()
        os.fsync(f.fileno())
    os.replace(temporary,probe/"artifact.txt")
    link=probe/"relative-link"
    if not link.is_symlink(): link.symlink_to("artifact.txt")
    result={"sha256":hashlib.sha256(content).hexdigest()}
elif action=="read":
    if probe.is_symlink(): raise RuntimeError("probe must not be a symlink")
    content=(probe/"artifact.txt").read_bytes()
    linked=(probe/"relative-link").read_bytes()
    if content!=linked: raise RuntimeError("relative link differs from file")
    result={"sha256":hashlib.sha256(content).hexdigest(),
        "link":os.readlink(probe/"relative-link")}
elif action=="cleanup":
    if probe.is_symlink(): raise RuntimeError("probe must not be a symlink")
    if probe.exists():
        for name in ("artifact.txt","relative-link","stage-a","stage-b"):
            (probe/name).unlink(missing_ok=True)
        probe.rmdir()
    result={"removed":True}
else: raise RuntimeError("unknown action")
print(json.dumps(result))
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-a", required=True, help="SSH host or user@host")
    parser.add_argument("--host-b", required=True)
    parser.add_argument("--root-a", required=True, help="dedicated existing shared test directory")
    parser.add_argument("--root-b", required=True)
    parser.add_argument("--evidence", type=pathlib.Path, required=True, help="new JSON evidence file")
    parser.add_argument("--rounds", type=int, default=5)
    parser.add_argument("--attest-independent-hosts", action="store_true",
                        help="operator confirms separate physical hosts or independent-kernel VMs")
    args = parser.parse_args()
    if not args.attest_independent_hosts:
        parser.error("independent-host attestation required; two containers are insufficient")
    if not 1 <= args.rounds <= 100:
        parser.error("rounds must be 1..100")
    for host in (args.host_a, args.host_b):
        if host.startswith("-") or any(c.isspace() for c in host):
            parser.error("host must be a plain SSH destination, not options")
    # Reserve evidence before any remote mutation; never overwrite an earlier run.
    with args.evidence.open("x") as evidence:
        token = "042-probe-" + uuid.uuid4().hex
        report = {"started_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  "token": token, "scope": "storage subset only; T013 not completed",
                  "operator_attests_independent_hosts": True, "events": []}

        def remote(host, root, action, **extra):
            payload = {"root": root, "token": token, "action": action, **extra}
            command = shlex.join(["python3", "-c", REMOTE, json.dumps(payload)])
            completed = subprocess.run(
                ["ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes",
                 "-o", "ConnectTimeout=8", host, command],
                text=True, capture_output=True, timeout=25, check=True)
            return json.loads(completed.stdout)

        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                a = pool.submit(remote, args.host_a, args.root_a, "info")
                b = pool.submit(remote, args.host_b, args.root_b, "info")
                info_a, info_b = a.result(), b.result()
            report["nodes"] = {"a": info_a, "b": info_b}
            if info_a["boot_id"] == info_b["boot_id"]:
                raise RuntimeError("same kernel boot ID; containers do not qualify")
            for info in (info_a, info_b):
                mount = info["mount"]["filesystems"][0]
                if mount["fstype"] not in ("nfs", "nfs4", "ceph", "fuse.ceph"):
                    raise RuntimeError("mount must be NFS or CephFS, not a local/container volume")
                if "nocto" in mount["options"].split(","):
                    raise RuntimeError("nocto mount is unsupported")
            remote(args.host_a, args.root_a, "init")
            for iteration in range(args.rounds):
                for writer, host, root, reader_host, reader_root in (
                    ("a", args.host_a, args.root_a, args.host_b, args.root_b),
                    ("b", args.host_b, args.root_b, args.host_a, args.root_a)):
                    content = f"{token}:{iteration}:{writer}:" + "x" * 65536
                    expected = hashlib.sha256((content + "\n").encode()).hexdigest()
                    remote(host, root, "publish", writer=writer, content=content)
                    actual = remote(reader_host, reader_root, "read")
                    if actual["sha256"] != expected or actual["link"] != "artifact.txt":
                        raise RuntimeError("cross-host close-to-open/link verification failed")
                    report["events"].append({"writer": writer, "round": iteration,
                                             "sha256": expected,
                                             "observed_at": datetime.datetime.now(datetime.timezone.utc).isoformat()})
            report["result"] = "PASS_STORAGE_SUBSET"
        except Exception as failure:
            # Do not emit SSH stderr/configuration or remote command payloads.
            report["result"] = "FAILED"
            report["error_type"] = type(failure).__name__
            if isinstance(failure, RuntimeError):
                report["error"] = str(failure)
        finally:
            # Only our unique fixture and fixed filenames; no recursive deletion.
            try:
                remote(args.host_a, args.root_a, "cleanup")
                report["cleanup"] = "complete"
            except Exception as failure:
                report["cleanup"] = "manual inspection required: " + type(failure).__name__
            json.dump(report, evidence, indent=2)
            evidence.write("\n")
        print(f"{report['result']}: {args.evidence}")
        return 0 if report["result"] == "PASS_STORAGE_SUBSET" else 1


if __name__ == "__main__":
    sys.exit(main())
