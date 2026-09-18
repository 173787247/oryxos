# 042 real shared-volume acceptance kit

## Environment discovery — 2026-09-18 UTC

Read-only discovery, including approved access outside the sandbox, found:

- One Docker Desktop engine, kernel `6.6.87.2-microsoft-standard-WSL2`. `default` uses its Unix socket; `desktop-linux` is a Windows named-pipe context, not evidence of another host.
- Existing `oryxos-local`, its PostgreSQL and mem0 services are running. They were neither reconfigured nor used for fault injection. Docker networks are local bridge networks (`kind`, `oryxos-local_default`, `mem0_default`, etc.).
- Sole Kubernetes context: `kind-oryxos-ci`. Its sole discovered control-plane container is stopped (exit 137), image kindest/node v1.32.0. The API endpoint refuses connections even outside the sandbox. Restarting this container would restore a local kind environment, not two independent kernels.
- No NFS/CIFS/Ceph mount was found in the available workspace mount namespace. No remote SSH destinations were provided. Discovery did not read keys, environment variables, database contents or Docker container environment settings.
- `/dev/kvm` exists outside the sandbox (root:kvm, mode 0660), but this user is not in group kvm and opening it returns EACCES. `qemu-system-x86_64`, `qemu-img`, `virsh`, `cloud-localds` and `virt-install` are unavailable; no libvirt image directories were found. VMX support alone does not prove usable nested virtualization.

There is **no immediately usable true two-node acceptance environment**. No containers were started because that would not remove this gap. Two independent VMs could be a future route, but require approved hypervisor access, installed tooling, guest images, guest networking and a shared NFS/Ceph export. These resources were not installed or provisioned by this investigation. Two VMs on one physical host can validate separate clients/kernels, but cannot demonstrate tolerance of losing that physical host.

## Required isolated resources

Provide two SSH destinations on separate physical hosts or independent-kernel VMs, with Python 3, findmnt and the same dedicated NFS/CephFS export mounted on both. Supply a separate test PostgreSQL database, a test workspace identity and isolated application endpoints on each node. Existing user PostgreSQL/oryxos/mem0 data must not be used. Fault injection must be scoped to these test instances and mounts. Full restore-to-new-node evidence also requires a third clean node or rebuilding one stopped test node.

An operator must confirm host identity/hypervisor topology; distinct hostnames, container IDs or Kubernetes Node objects alone are insufficient. The script rejects equal boot IDs, but that check is not a substitute for topology evidence (privileged containers can virtualize identifiers).

## Runnable storage subset

`042-shared-storage-probe.py` runs through SSH with existing trusted host keys, BatchMode authentication and bounded command timeouts. On **each test node**, an administrator first creates a dedicated shared test directory and opts it in with an empty `.042-acceptance-allowed` file. Do not select a production workspace. The directory may have different mount paths on A and B, but must refer to the same shared directory.

```sh
python3 scripts/042-shared-storage-probe.py \
  --host-a acceptance-a --root-a /mnt/acceptance/042-probe \
  --host-b acceptance-b --root-b /mnt/acceptance/042-probe \
  --attest-independent-hosts \
  --rounds 20 --evidence /tmp/042-storage-evidence.json
```

The script records kernel boot IDs, filesystem type/source/options, UTC timestamps and content hashes. It rejects local filesystems and `nocto`, writes only a unique `042-probe-<uuid>` directory, alternates atomic file replacement between both nodes, checks immediate close-to-open visibility and a relative symlink on the other node, then deletes only its fixed fixture files. Evidence output is exclusive-created. Failures retain their outcome; cleanup failure identifies the unique directory for inspection. Check timeout failures for a stuck remote SSH child before manual cleanup. No environment/key dump is collected.

`PASS_STORAGE_SUBSET` proves only these observed operations on the configured mount. It does not prove concurrent-reader atomicity, durability under power loss, application reconciliation, failure fencing or T013 completion. SSH timings are not SC-002 convergence timings. Mount options in the evidence are operator-visible infrastructure metadata; review before sharing outside the team.

## Remaining application evidence matrix

Run on the isolated application deployment built from the reviewed commit, with direct A/B endpoints (no load-balancer ambiguity), same shared identity, unique instance IDs and separate test DB. Record commit/image digest, application settings excluding credentials, node topology, export/PVC identity, mount options and UTC start/end. Save status codes, redacted bodies and X-Workspace-Revision/Outcome; never save Authorization headers.

| Scenario | Required observation and retained evidence |
| --- | --- |
| Cross-node edits and bindings | A creates/edits Agent, Skill and knowledge fixtures; B reads content and inspects relative links. Record canonical hashes and readlink strings. |
| SC-002 runtime convergence | Follow specs/042-workspace-storage/quickstart.md: 20 unique-marker creates/updates/deletes, publication response to B **runtime-registry-dependent** observation, each normal-notification result ≤3s with default 1s polling. Editable canonical GET is not evidence of runtime convergence. Record sampling interval and all samples. |
| Missed version notification | Only on the test workspace, publish an atomic file change without version bump immediately after a completed reconciliation. Observe B within configured reconciliation interval plus measured reload time. Record both configuration and measured timing. |
| Stale-edit conflict | Read one revision, submit two differing writes with that same If-Match to A/B. Exactly one succeeds; other conflicts without replacing the winner. Save responses and final hash. |
| Output isolation/partial publication | Two agents/runs emit the same basename; record distinct final paths and hashes. Interrupt a download/stream and confirm old final survives and temporary/alias API reads are denied. |
| Wrong/missing identity | On an isolated node/mount only, substitute wrong identity or remove the mount. Observe bounded readiness failure and new-write refusal, and prove no local replacement workspace was created. Restore and record recovery. Do not unmount the user's existing volumes. |
| Stop A | Stop only the isolated A application, then read published artifacts and runtime behavior via B. This is application-node failure evidence, not NFS-server HA. |
| Interrupted management writer | Interrupt a test writer mid-publication; verify no time-based reservation takeover. Stop every writer before documented offline recovery. Save journal/recovery outcome and old/new hashes. |
| Backup/new-node restore | Stop test writers, preserve symlinks (`rsync -a`, never `-L`), generate separate regular-file SHA-256 and readlink manifests, restore on a clean node and compare. Coordinate independent test DB backup as documented. |

Only completed rows with real environment evidence can close T013. An unavailable resource or unexecuted row remains explicitly incomplete; the storage script never changes tasks.md/acceptance.md.

## Checks actually performed on this kit

Python CLI help and syntax parsing of both controller and embedded remote program passed locally. An isolated local temporary-directory smoke check also exercised fixture creation, replacement, hash/link reading and cleanup successfully; this validates script mechanics only. No SSH two-node run or application fault scenario was executed because the required external environment is absent. Therefore T013 remains incomplete.
