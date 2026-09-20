#!/usr/bin/env python3
import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('security_assumptions', Path(__file__).with_name('check-security-assumptions.py'))
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class AssumptionGuardTest(unittest.TestCase):
    def test_sensitive_api_in_source_or_reflection_requires_review(self):
        for source in ['new SpelExpressionParser()', 'Class.forName("org.springframework.web.servlet.view.xslt.XsltView")', '@ModelAttribute Form input', 'Sort.by(request.sort())']:
            with self.subTest(source=source):
                self.assertTrue(guard.inspect_text(Path('Controller.java'), source))

    def test_reactive_and_reader_dependencies_require_review(self):
        for artifact in ['spring-boot-starter-webflux', 'spring-ai-pdf-document-reader', 'spring-ai-transformers', 'spring-security-web']:
            self.assertTrue(guard.inspect_text(Path('pom.xml'), '<artifactId>' + artifact + '</artifactId>'))

    def test_configuration_can_invalidate_assessment(self):
        self.assertTrue(guard.inspect_text(Path('application.yml'), 'web-application-type: reactive'))

    def test_current_fixed_sort_is_narrowly_accepted(self):
        self.assertFalse(guard.inspect_text(Path('JpaSessionManager.java'), guard.REVIEWED_SORT))
        self.assertTrue(guard.inspect_text(Path('NewController.java'), guard.REVIEWED_SORT))
        self.assertTrue(guard.inspect_text(Path('JpaSessionManager.java'), 'Sort.by(input)'))

    def test_root_dependencies_and_configuration_examples_are_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'pom.xml').write_text('<artifactId>spring-ai-transformers</artifactId>')
            config = root / 'config/application.yml.example'
            config.parent.mkdir()
            config.write_text('web-application-type: reactive')
            self.assertEqual(2, len(guard.inspect_repository(root)))

    def test_production_resource_and_source_scanning(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'oryxos-web/src/main/java/C.java'
            source.parent.mkdir(parents=True)
            source.write_text('new AesBytesEncryptor(password, salt)')
            self.assertEqual(1, len(guard.inspect_repository(root)))
            source.write_text('new SafeService()')
            self.assertEqual([], guard.inspect_repository(root))


if __name__ == '__main__':
    unittest.main()
