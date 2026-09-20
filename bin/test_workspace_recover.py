import base64
import importlib.util
from pathlib import Path
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("workspace-recover.py")
SPEC = importlib.util.spec_from_file_location("workspace_recover", SCRIPT)
RECOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECOVERY)


class WorkspaceRecoveryTest(unittest.TestCase):
    def test_supplied_identity_requires_existing_marker_before_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, "identity marker"):
                RECOVERY.recover(
                    root,
                    apply=True,
                    identity="production",
                    confirm_writers_stopped=True,
                )
            self.assertEqual([], list(root.iterdir()))

    def test_interrupted_files_restore_and_committed_files_remain(self):
        for committed in (False, True):
            with self.subTest(committed=committed), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                agent = root / "agents" / "demo"
                journal = agent / ".workspace-transaction"
                journal.mkdir(parents=True)
                (agent / "AGENT.md").write_text("replacement")
                (agent / "new.txt").write_text("new")
                (journal / "0.old").write_text("original")
                encoded = lambda name: base64.b64encode(name.encode()).decode()
                (journal / "manifest").write_text(
                    "0\ttrue\t" + encoded("AGENT.md") + "\n"
                    "1\tfalse\t" + encoded("new.txt") + "\n"
                )
                if committed:
                    (journal / "committed").write_text("true\n")
                inspected = RECOVERY.recover(root)
                self.assertFalse(inspected["applied"])
                self.assertEqual("replacement", (agent / "AGENT.md").read_text())
                RECOVERY.recover(root, apply=True, confirm_writers_stopped=True)
                self.assertEqual("replacement" if committed else "original",
                                 (agent / "AGENT.md").read_text())
                self.assertEqual(committed, (agent / "new.txt").exists())
                self.assertFalse(journal.exists())
                archives = list(agent.glob(".workspace-recovered-*"))
                self.assertEqual(1, len(archives))
                self.assertEqual("original", (archives[0] / "0.old").read_text())

    def test_ownerless_reservation_requires_explicit_sentinel_and_offline_consent(self):
        for leftover in (None, "operation"):
            with self.subTest(leftover=leftover), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                lock = (root / ".workspace-write")
                lock.mkdir()
                if leftover:
                    (lock / leftover).write_text("interrupted\n")

                inspected = RECOVERY.recover(root)
                self.assertEqual(RECOVERY.OWNERLESS_ACK, inspected["reservation"])
                self.assertTrue(inspected["ownerless"])

                with self.assertRaisesRegex(ValueError, "writers must be stopped"):
                    RECOVERY.recover(
                        root,
                        apply=True,
                        token=RECOVERY.OWNERLESS_ACK,
                        confirm_writers_stopped=False,
                    )
                with self.assertRaisesRegex(ValueError, "ownerless acknowledgement"):
                    RECOVERY.recover(
                        root,
                        apply=True,
                        token="wrong",
                        confirm_writers_stopped=True,
                    )

                recovered = RECOVERY.recover(
                    root,
                    apply=True,
                    token=RECOVERY.OWNERLESS_ACK,
                    confirm_writers_stopped=True,
                )
                self.assertTrue(recovered["applied"])
                self.assertFalse(lock.exists())
                self.assertEqual(
                    1,
                    len(list(root.glob(".workspace-recovered-reservation-*"))),
                )


if __name__ == "__main__":
    unittest.main()
