#!/usr/bin/env python3
"""Guard the source/configuration assumptions behind time-limited CVE exclusions.

This is a conservative change detector, NOT call-graph analysis or a proof that
all transitive/library/plugin code is unreachable. Hits require security review;
never add an allowlist entry merely to make this check pass.
"""
import argparse
import re
import sys
from pathlib import Path

# Include fully qualified names, imports, configuration values and reflection strings.
API_PATTERN = re.compile(
    r"\b(?:XsltView|XsltViewResolver|UrlHandlerFilter|UrlFileNameViewController|"
    r"FreeMarkerConfigurer|FreeMarkerViewResolver|SpringTemplateLoader|EscapedErrors|"
    r"SpelExpressionParser|SpelExpression|DataBinder|WebDataBinder|BeanWrapper|"
    r"DirectFieldAccessor|AutoPopulatingList|LazyList|"
    r"RSocketMessageHandler|PartEventHttpMessageReader|HandshakeWebSocketService|"
    r"AesBytesEncryptor|AesCbcBytesEncryptor|Encryptors|"
    r"DigestAuthenticationFilter|KeyBasedPersistenceTokenService|"
    r"BalloonHashingPassword4jPasswordEncoder|Pbkdf2Password4jPasswordEncoder|"
    r"InMemoryOAuth2AuthorizationService|WebAuthnAuthenticationFilter|"
    r"TransformersEmbeddingModel|ResourceCacheService|TikaDocumentReader|"
    r"PagePdfDocumentReader|ParagraphPdfDocumentReader|TocPdfDocumentReader|"
    r"RouterFunction|RouterFunctions)\b"
)
IMPORT_PATTERN = re.compile(
    r"org\.springframework\.(?:web\.reactive\.(?:config|socket|function\.server)|web\.servlet\.function|"
    r"security\.(?:web|oauth2|config)|expression)\b|"
    r"org\.springframework\.ai\.(?:reader|transformers)\b|io\.rsocket\b"
)
ARTIFACT_PATTERN = re.compile(
    r"\b(?:spring-boot-starter-(?:webflux|rsocket|freemarker|security|oauth2-authorization-server)|"
    r"spring-ai-(?:pdf-document-reader|tika-document-reader|transformers|mcp)|"
    r"spring-security-(?:web|config|oauth2-authorization-server)|rsocket-core)\b"
)
CONFIG_PATTERN = re.compile(
    r"(?:web-application-type\s*[:=]\s*reactive|spring\.rsocket\.|"
    r"spring\.freemarker\.|spring\.ai\.(?:embedding\.transformer|model\.embedding\s*[:=]\s*transformers))",
    re.IGNORECASE,
)
# Existing application only uses this fixed order. New Sort APIs must be reviewed.
REVIEWED_SORT = 'Sort.by(Sort.Direction.DESC, "lastActiveAt")'


def inspect_text(path, text):
    findings = []
    if path.suffix == '.xml':
        text = re.sub(r'<!--.*?-->', lambda match: '\n' * match.group().count('\n'), text, flags=re.DOTALL)
    patterns = [API_PATTERN, IMPORT_PATTERN, ARTIFACT_PATTERN, CONFIG_PATTERN]
    for line_number, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith(('//', '*', '/*', '#')):
            continue
        for pattern in patterns:
            match = pattern.search(line)
            if match:
                findings.append(f"{path}:{line_number}: reassess security exclusion: {match.group()}")
                break
        if path.suffix == '.java' and re.search(r'\b(?:Sort|JpaSort)\s*\.', line):
            reviewed = path.name == 'JpaSessionManager.java' and REVIEWED_SORT in line
            if not reviewed:
                findings.append(f"{path}:{line_number}: unreviewed repository sort expression")
        if path.suffix == '.java' and re.search(r'@(ModelAttribute|InitBinder)\b', line):
            findings.append(f"{path}:{line_number}: new Spring property-binding surface")
    return findings


def inspect_repository(root):
    findings = []
    root_pom = root / 'pom.xml'
    if root_pom.exists():
        findings.extend(inspect_text(Path('pom.xml'), root_pom.read_text()))
    # Production source/resources only; test fixtures deliberately exercise failure conditions.
    for module in sorted(root.glob('oryxos-*')):
        if not module.is_dir():
            continue
        paths = [module / 'pom.xml']
        source = module / 'src/main'
        if source.exists():
            paths.extend(source.rglob('*'))
        for path in paths:
            if not path.is_file() or path.suffix not in {'.java', '.xml', '.yml', '.yaml', '.properties'}:
                continue
            if 'static' in path.parts or 'node_modules' in path.parts:
                continue
            findings.extend(inspect_text(path.relative_to(root), path.read_text()))
    for base in (root / 'config', root / 'charts', root / 'docker'):
        if base.exists():
            for path in base.rglob('*'):
                if path.is_file() and (path.suffix in {'.yaml', '.yml', '.properties'} or path.name.endswith(('.yaml.example', '.yml.example', '.properties.example'))):
                    findings.extend(inspect_text(path.relative_to(root), path.read_text()))
    return findings


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--dependency-tree', type=Path, help='Optional current Maven dependency:tree output')
    args = parser.parse_args()
    findings = inspect_repository(args.root.resolve())
    if args.dependency_tree:
        findings.extend(inspect_text(args.dependency_tree, args.dependency_tree.read_text()))
    if findings:
        print('\n'.join(findings), file=sys.stderr)
        return 1
    print('Security assumption change detector passed; this is not a reachability proof.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
