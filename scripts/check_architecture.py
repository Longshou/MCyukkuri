#!/usr/bin/env python3
"""Read-only, advisory Java architecture checks. Python 3.10+, standard library only.

This is a token checker, not a Java compiler or a whole-program type/dataflow
analyser. Unknown candidate ownership is an explicit coverage error. Exceptions
are reviewed permissions, never an automatically accepted snapshot of a tree.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys

JAVA_ROOT = Path("src/main/java")
OWNER_ROOT = "io/github/longshou/mcyukkuri/"
SERVER_TYPES = {"YukkuriEntity", "HatBoatEntity"}
RULES = {"CLIENT_COMPONENT", "PERSISTENT_DATA", "SPECIES_FLAG", "INLINE_FQN"}
OPERATORS = tuple(sorted((">>>=", ">>=", "<<=", ">>>", "...", "::", "->", "++", "--", "==", "<=",
                          ">=", "!=", "&&", "||", "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "<<", ">>"),
                         key=len, reverse=True))


class CoverageError(ValueError):
    pass


@dataclass(frozen=True)
class Token:
    value: str
    line: int
    column: int
    kind: str = "code"


@dataclass(frozen=True)
class Scope:
    start: int
    end: int
    signature: str


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    column: int
    rule: str
    message: str
    signature: str = ""
    token_hash: str = ""
    subject: str = ""
    coverage: bool = False


def lex(source: str) -> list[Token]:
    """Keep literal tokens for hashes/keys, but never inspect their contents as code."""
    out = []
    i, line, col = 0, 1, 1

    def consume(end: int) -> str:
        nonlocal i, line, col
        value = source[i:end]
        lines = value.split("\n")
        if len(lines) > 1:
            line += len(lines) - 1
            col = len(lines[-1]) + 1
        else:
            col += len(value)
        i = end
        return value

    # Java processes Unicode escapes even inside comments, before tokenization.
    # Reject rather than pretend that an untranslated identifier/comment is safe.
    if re.search(r"\\u+[0-9a-fA-F]{4}", source):
        raise CoverageError("Java Unicode escape requires manual lexical review")
    while i < len(source):
        char = source[i]
        if char.isspace():
            consume(i + 1)
            continue
        if source.startswith("//", i):
            end = source.find("\n", i)
            consume(len(source) if end < 0 else end)
            continue
        if source.startswith("/*", i):
            end = source.find("*/", i + 2)
            if end < 0:
                raise CoverageError(f"unterminated comment at {line}:{col}")
            consume(end + 2)
            continue
        at_line, at_col = line, col
        if char in "\"'":
            block = source.startswith('"""', i)
            delimiter = '"""' if block else char
            end = i + len(delimiter)
            while end < len(source):
                if source[end] == "\\":
                    end += 2
                    continue
                if source.startswith(delimiter, end):
                    end += len(delimiter)
                    break
                if not block and source[end] in "\r\n":
                    raise CoverageError(f"newline in literal at {at_line}:{at_col}")
                end += 1
            else:
                raise CoverageError(f"unterminated literal at {at_line}:{at_col}")
            out.append(Token(consume(end), at_line, at_col, "literal"))
            continue
        match = re.match(r"[\w$]+", source[i:], re.UNICODE)
        if match:
            value = consume(i + len(match.group()))
            out.append(Token(value, at_line, at_col, "word"))
        elif operator := next((op for op in OPERATORS if source.startswith(op, i)), None):
            out.append(Token(consume(i + len(operator)), at_line, at_col))
        elif char in "(){}[].,;@=><!~?:+-*/&|^%":
            out.append(Token(consume(i + 1), at_line, at_col))
        else:
            raise CoverageError(f"unsupported lexical character {char!r} at {line}:{col}")
    return out


def digest(tokens: list[Token]) -> str:
    # JSON retains token boundaries and literal contents; whitespace/comments do
    # not matter. Hashing an entire owning method intentionally fails closed on
    # unrelated edits to it, so a new use cannot inherit the old permission.
    data = json.dumps([t.value for t in tokens], ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def identifier(value: str) -> bool:
    return bool(re.fullmatch(r"[^\W\d][\w$]*|\$[\w$]*", value, re.UNICODE))


class JavaSource:
    def __init__(self, path: str, source: str):
        self.path = path
        self.tokens = lex(source)
        self.values = [t.value if t.kind != "literal" else "<literal>" for t in self.tokens]
        self.pairs = {}
        stack = []
        for i, value in enumerate(self.values):
            if value in ("(", "{", "["):
                stack.append((value, i))
            elif value in (")", "}", "]"):
                if not stack or stack[-1][0] != {")": "(", "}": "{", "]": "["}[value]:
                    raise CoverageError(f"unbalanced delimiter at {self.tokens[i].line}")
                _, start = stack.pop()
                self.pairs[start] = i
                self.pairs[i] = start
        if stack:
            raise CoverageError("unclosed delimiter")
        self.declarations = set()
        self.excluded = set()
        self.package = ""
        self.imports = {}
        self.import_roots = set()
        for i, value in enumerate(self.values):
            if value not in ("package", "import"):
                continue
            try:
                end = self.values.index(";", i)
            except ValueError as error:
                raise CoverageError("unterminated package/import") from error
            parts = self.values[i + 1:end]
            if value == "package":
                self.package = "".join(parts)
            elif parts:
                imported = [p for p in parts if p != "static"]
                if imported:
                    self.import_roots.add(imported[0])
                if parts[-1] != "*":
                    full = "".join(imported)
                    self.imports[full.rsplit(".", 1)[-1]] = full
            self.excluded.update(range(i, end + 1))
        self.classes = self._classes()
        self.methods = self._methods()

    def _classes(self) -> list[Scope]:
        result = []
        for i, value in enumerate(self.values[:-1]):
            if value not in ("class", "interface", "enum", "record"):
                continue
            if i and self.values[i - 1] == ".":  # class literal
                continue
            if not identifier(self.values[i + 1]):
                continue
            j = i + 2
            while j < len(self.values) and self.values[j] not in ("{", ";"):
                if self.values[j] == "(":
                    j = self.pairs[j]
                j += 1
            if j < len(self.values) and self.values[j] == "{":
                result.append(Scope(j, self.pairs[j], self.values[i + 1]))
        return result

    def class_name(self, i: int) -> str:
        return ".".join(c.signature for c in self.classes if c.start < i < c.end)

    def _methods(self) -> list[Scope]:
        result = []
        controls = {"if", "for", "while", "switch", "catch", "synchronized", "try", "new"}
        v = self.values
        for opening, value in enumerate(v):
            if value != "(" or opening == 0:
                continue
            name_at = opening - 1
            if not identifier(v[name_at]) or v[name_at] in controls:
                continue
            if name_at and v[name_at - 1] in (".", "::", "new", "@"):
                continue
            closing = self.pairs[opening]
            body = closing + 1
            if body < len(v) and v[body] == "throws":
                body += 1
                while body < len(v) and v[body] not in ("{", ";"):
                    body += 1
            if body >= len(v) or v[body] not in ("{", ";"):
                continue
            # Method declarations occur at class-member depth. Reject calls in
            # statements/lambdas, but support nested/local classes.
            owner_classes = [c for c in self.classes if c.start < opening < c.end]
            if not owner_classes:
                continue
            cls = owner_classes[-1]
            containers = [a for a, b in self.pairs.items()
                          if a < opening < b and v[a] == "{" and a >= cls.start]
            if containers != [cls.start]:
                continue
            start = name_at
            while start > cls.start + 1 and v[start - 1] not in (";", "{", "}"):
                start -= 1
            prefix = v[start:name_at]
            if "=" in prefix or "->" in prefix:
                continue
            if not prefix and v[name_at] != cls.signature:  # constructor
                continue
            self.declarations.add(name_at)
            if v[body] == ";":
                continue
            signature = self.class_name(opening) + "#" + " ".join(v[name_at:closing + 1])
            result.append(Scope(start, self.pairs[body], signature))
        return result

    def method(self, i: int) -> Scope | None:
        scopes = [m for m in self.methods if m.start <= i <= m.end]
        return min(scopes, key=lambda m: m.end - m.start) if scopes else None

    def finding(self, i: int, rule: str, message: str, subject: str = "",
                coverage: bool = False) -> Finding:
        method = self.method(i)
        token = self.tokens[i]
        return Finding(self.path, token.line, token.column, rule, message,
                       method.signature if method else "", digest(self.tokens[method.start:method.end + 1])
                       if method else "", subject, coverage)


def access_operator(java: JavaSource, i: int) -> int | None:
    """Locate . / :: through optional explicit Java method type arguments."""
    v = java.values
    j = i - 1
    if j >= 0 and v[j] in {">", ">>", ">>>"}:
        depth = len(v[j])
        j -= 1
        while j >= 0 and depth:
            if v[j] in {">", ">>", ">>>"}:
                depth += len(v[j])
            elif v[j] == "<":
                depth -= 1
            j -= 1
        if depth:
            return None
    return j if j >= 0 and v[j] in (".", "::") else None


def receiver_type(java: JavaSource, i: int) -> str | None:
    """Finite lexical receiver inference; uncertain chains remain coverage errors."""
    v = java.values
    operator = access_operator(java, i)
    if operator is None or operator < 1:
        return None
    receiver = v[operator - 1]
    if receiver in SERVER_TYPES | {"YukkuriVariant"}:
        return receiver  # unbound method reference
    if not identifier(receiver):
        return None
    # this.name denotes a field even when a parameter/local shadows that name.
    # Other qualified chains need type/member resolution beyond this checker.
    fields_only = operator >= 3 and v[operator - 2] == "." and v[operator - 3] == "this"
    if operator >= 2 and v[operator - 2] == "." and not fields_only:
        return None
    # A word immediately before a receiver use is not a type declaration:
    # `else e.body()` and `assert e.body()` must never overwrite parameter e.
    non_types = set(("abstract assert break case catch class const continue default do else enum extends final "
                     "finally for goto if implements import instanceof interface native new package private "
                     "protected public return static strictfp super switch synchronized this throw throws "
                     "transient try volatile while true false null yield record sealed permits when").split())
    method = java.method(i)

    def resolve(name: str, until: int, field: bool = False, depth: int = 0) -> str | None:
        if depth > 8:
            return None
        result = None
        for j in range(1, until):
            if v[j] != name or j + 1 >= len(v):
                continue
            other_method = java.method(j)
            if field and other_method is not None:
                continue
            if other_method is not None and other_method != method:
                continue
            if java.class_name(j) != java.class_name(i):
                continue
            if any(a < j < b < i for a, b in java.pairs.items() if v[a] == "{"):
                continue
            previous = v[j - 1]
            # Restrict to actual simple variable/parameter declaration shapes;
            # member access and arbitrary expression words are not declarations.
            if (not identifier(previous) or previous in non_types
                    or v[j + 1] not in {"=", ";", ",", ")", ":", "["}):
                continue
            result = previous
            if previous == "var":
                result = "var"
                if j + 3 < until and v[j + 1] == "=" and v[j + 3] == ";":
                    result = resolve(v[j + 2], j, depth=depth + 1) or "var"
                if j + 4 < until and v[j + 1:j + 4] == [":", "YukkuriVariant", "."]:
                    result = "YukkuriVariant"
        return result

    return resolve(receiver, i, fields_only)


def scan(java: JavaSource, getters: dict[str, list[str]], roots: set[str]) -> list[Finding]:
    findings = []
    v = java.values
    client = java.package == "io.github.longshou.mcyukkuri.client" or java.package.startswith(
        "io.github.longshou.mcyukkuri.client.") or "/client/" in java.path
    for i, value in enumerate(v):
        if i in java.excluded or i in java.declarations or java.tokens[i].kind == "literal":
            continue
        call = i + 1 < len(v) and v[i + 1] == "("
        operator = access_operator(java, i)
        ref = operator is not None and v[operator] == "::"
        if value == "getPersistentData" and (call or ref):
            findings.append(java.finding(i, "PERSISTENT_DATA", "raw persistent-data access", value))
        zero_args = call and i + 2 < len(v) and v[i + 2] == ")"
        if value in {"isHouse", "isPredator"} and (zero_args or ref):
            typ = receiver_type(java, i)
            if typ == "YukkuriVariant":
                findings.append(java.finding(i, "SPECIES_FLAG", "raw species flag access", value))
            elif typ is None or typ == "var":
                findings.append(java.finding(i, "COVERAGE", "unknown receiver for species flag " + value,
                                             value, True))
        if client and (zero_args or ref or (call and value == "component")) and any(
                value in names for names in getters.values()):
            typ = receiver_type(java, i)
            if typ in getters and value in getters[typ]:
                findings.append(java.finding(i, "CLIENT_COMPONENT", f"{typ}.{value} is server-owned", value))
            elif typ is None or typ == "var":
                findings.append(java.finding(i, "COVERAGE", f"unknown receiver for component candidate {value}",
                                             value, True))
            # An explicit unrelated type denotes a different symbol; this finite
            # inference does not resolve inheritance/generic factory return types.
        # An inline fully qualified type starts at a known package root. It must
        # contain a type segment, so this.G and ordinary variable chains do not
        # become type references. Package/import statements were excluded above.
        if value in roots and (i == 0 or v[i - 1] != "."):
            j = i
            parts = [value]
            while j + 2 < len(v) and v[j + 1] == "." and identifier(v[j + 2]):
                parts.append(v[j + 2])
                j += 2
            first_type = next((k for k, part in enumerate(parts[1:], 1)
                               if part[0].isupper()), None)
            if first_type is not None and first_type >= 1:
                type_end = first_type
                while type_end + 1 < len(parts) and parts[type_end + 1][0].isupper():
                    type_end += 1
                subject = ".".join(parts[:type_end + 1])
                findings.append(java.finding(i, "INLINE_FQN", "inline qualified type " + subject, subject))
            elif len(parts) >= 3:
                findings.append(java.finding(i, "COVERAGE", "unresolved lowercase qualified-name candidate "
                                             + ".".join(parts), coverage=True))
    for f in list(findings):
        if not f.coverage and not f.signature:
            # Unregistered initializers/anonymous ownership must not accidentally
            # match a blank signature. A future intentional permission needs an
            # explicit, reviewed scope representation.
            findings.append(Finding(f.path, f.line, f.column, "COVERAGE",
                                    f"cannot locate method ownership for {f.rule}", coverage=True))
    return findings


def validate_permissions(data: dict) -> None:
    if data.get("schema") != 1 or not re.fullmatch(r"[0-9a-f]{40}", data.get("reviewed_base", "")):
        raise CoverageError("invalid permission schema/reviewed_base")
    seen = set()
    for item in data.get("permissions", []):
        required = {"rule", "path", "signature", "token_hash", "subjects", "reason", "receiver", "keys"}
        if not required <= item.keys() or item["rule"] not in RULES:
            raise CoverageError("incomplete permission entry")
        if not item["reason"] or not item["receiver"] or not item["signature"]:
            raise CoverageError("permission requires a specific owner/receiver/reason")
        if not item["path"].startswith("src/main/java/") or ".." in Path(item["path"]).parts:
            raise CoverageError("invalid permission path")
        if not re.fullmatch(r"[0-9a-f]{64}", item["token_hash"]):
            raise CoverageError("invalid token hash")
        if not item["subjects"] or any(type(n) is not int or n < 1 for n in item["subjects"].values()):
            raise CoverageError("permission needs exact positive subject counts")
        accesses = item.get("accesses", [])
        if len(accesses) != sum(item["subjects"].values()):
            raise CoverageError("each approved access needs its own receiver/key/reason record")
        for ordinal, access in enumerate(accesses, 1):
            if access.get("ordinal") != ordinal or not access.get("reason") or not access.get("receiver"):
                raise CoverageError("invalid per-access review")
            if item["rule"] == "PERSISTENT_DATA" and not access.get("keys"):
                raise CoverageError("persistent access requires reviewed key operations")
        key = (item["rule"], item["path"], item["signature"])
        if key in seen:
            raise CoverageError("duplicate permission owner")
        seen.add(key)
    constant_keys = set()
    for item in data["constant_dependencies"]:
        key = (item["path"], item["class"], item["field"])
        if key in constant_keys or not item["reason"] or not re.fullmatch(r"[0-9a-f]{64}", item["token_hash"]):
            raise CoverageError("invalid constant dependency permission")
        constant_keys.add(key)


def constant_declarations(java: JavaSource, class_name: str, field: str) -> list[list[Token]]:
    """Locate a reviewed external key constant without locking unrelated fields."""
    declarations = []
    v = java.values
    for i in range(1, len(v) - 1):
        if v[i] != field or v[i + 1] != "=" or java.class_name(i) != class_name or java.method(i):
            continue
        start = i
        while start > 0 and v[start - 1] not in {";", "{", "}"}:
            start -= 1
        end = i + 2
        while end < len(v) and v[end] != ";":
            end += 1
        if end < len(v) and "static" in v[start:i] and "final" in v[start:i] and "String" in v[start:i]:
            declarations.append(java.tokens[start:end + 1])
    return declarations


def check(root: Path, data: dict) -> tuple[list[Finding], int, int]:
    validate_permissions(data)
    sources = []
    findings = []
    base = root / JAVA_ROOT
    if not base.is_dir():
        raise CoverageError("missing src/main/java")
    for path in sorted(base.rglob("*.java")):
        rel = path.relative_to(root).as_posix()
        try:
            java = JavaSource(rel, path.read_text(encoding="utf-8-sig"))
            expected = path.relative_to(base).parent.as_posix().replace("/", ".")
            if java.package != expected:
                findings.append(Finding(rel, 1, 1, "COVERAGE", "package/path mismatch", coverage=True))
            sources.append(java)
        except (CoverageError, UnicodeError, OSError) as error:
            findings.append(Finding(rel, 1, 1, "COVERAGE", str(error), coverage=True))
    if not sources:
        raise CoverageError("no readable Java sources")
    roots = {"java", "javax", "net", "org", "com", "io"}
    roots.update(java.package.split(".")[0] for java in sources if java.package)
    for java in sources:
        roots.update(java.import_roots)
    for java in sources:
        findings.extend(scan(java, data["component_getters"], roots))
    for item in data["constant_dependencies"]:
        owner = next((java for java in sources if java.path == item["path"]), None)
        declarations = constant_declarations(owner, item["class"], item["field"]) if owner else []
        if len(declarations) != 1 or digest(declarations[0]) != item["token_hash"]:
            findings.append(Finding(item["path"], 1, 1, "PERMISSION_DRIFT",
                                    "reviewed NBT key constant changed or missing: " + item["class"] + "." + item["field"]))
    # Check getter declarations and registered component return types themselves,
    # not just the old method-name list. An added getter for a newly registered
    # ComponentKey type must be reviewed even when it has no client caller yet.
    component_types = set(data["component_return_types"].values())
    for java in sources:
        for i in range(len(java.values) - 2):
            if java.values[i:i + 2] == ["ComponentKey", "<"] and identifier(java.values[i + 2]):
                component_types.add(java.values[i + 2])
    for typ, names in data["component_getters"].items():
        owner = next((java for java in sources if java.path.endswith("/" + typ + ".java")), None)
        if owner is None:
            findings.append(Finding(str(JAVA_ROOT), 1, 1, "COVERAGE", "missing getter owner " + typ, coverage=True))
            continue
        for name in names:
            declarations = [i for i in owner.declarations if owner.values[i] == name]
            if len(declarations) != 1:
                findings.append(Finding(owner.path, 1, 1, "COVERAGE", "getter declaration changed: " + name, coverage=True))
            elif name != "component" and owner.values[declarations[0] - 1] != data["component_return_types"][name]:
                findings.append(Finding(owner.path, 1, 1, "COVERAGE", "getter return type changed: " + name, coverage=True))
            elif name != "component" and owner.values[declarations[0] + 2] != ")":
                findings.append(Finding(owner.path, 1, 1, "COVERAGE", "getter parameter list changed: " + name, coverage=True))
        for i in owner.declarations:
            method = owner.method(i)
            if (method and owner.class_name(i) == typ and "public" in owner.values[method.start:i]
                    and owner.values[i - 2] != "." and owner.values[i - 1] in component_types
                    and owner.values[i] not in names):
                findings.append(owner.finding(i, "COVERAGE", "new server-component getter requires review: " + owner.values[i],
                                              coverage=True))
    approved = set()
    for item in data["permissions"]:
        indexes = [i for i, f in enumerate(findings) if (f.rule, f.path, f.signature) ==
                   (item["rule"], item["path"], item["signature"])]
        actual = Counter(findings[i].subject for i in indexes)
        if actual != item["subjects"] or any(findings[i].token_hash != item["token_hash"] for i in indexes):
            findings.append(Finding(item["path"], 1, 1, "PERMISSION_DRIFT",
                                    "reviewed tokens/counts changed or permission is stale: " + item["signature"]))
        else:
            approved.update(indexes)
    remaining = [f for i, f in enumerate(findings) if i not in approved]
    return sorted(remaining, key=lambda f: (f.path, f.line, f.column, f.rule, f.message)), len(sources), len(approved)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="explicit read-only mode (also the default)")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="repository root; scans only src/main/java")
    parser.add_argument("--json", action="store_true", help="emit diagnostics as JSON to stdout")
    args = parser.parse_args()
    try:
        # Permissions always come from this tool's sibling, not an arbitrary
        # target tree's unreviewed config. There is no baseline/update command.
        data = json.loads(Path(__file__).with_name("architecture_exceptions.json").read_text(encoding="utf-8-sig"))
        findings, files, approved = check(args.root.resolve(), data)
        status = 2 if any(f.coverage for f in findings) else 1 if findings else 0
        if args.json:
            from dataclasses import asdict
            print(json.dumps({"files": files, "approved_accesses": approved, "exit_code": status,
                              "findings": [asdict(f) for f in findings]}, ensure_ascii=False, indent=2))
        else:
            for f in findings:
                print(f"{f.path}:{f.line}:{f.column}: {f.rule}: {f.message}")
            print(f"Architecture advisory: {files} Java files; {approved} reviewed accesses; {len(findings)} findings")
        return status
    except (CoverageError, OSError, UnicodeError, ValueError, KeyError, TypeError) as error:
        print(f"architecture checker coverage/configuration error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
