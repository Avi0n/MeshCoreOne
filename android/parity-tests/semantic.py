"""AndroidOnly: WP-501 Source-backed assertion/input/action proposals, never name-based equivalence."""

import argparse
from collections import defaultdict
import bisect
import hashlib
import json
from pathlib import Path
import re
import sys

import audit
from controller.errors import PortError
from controller.schema import digest, load_json
from oracle.reference import FrozenReference, SOURCE_SHA, json_bytes, repo_path, write_or_check
from oracle.swift import IDENTIFIER, NUMBER, Syntax, Token

GROUP_ROOT = "docs/android/parity/groups"
REVIEW_PROPOSALS = "docs/android/parity/review-proposals.json"
REVIEW_BINDINGS = "docs/android/parity/review-bindings.json"


def text_digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def kotlin_syntax(text):
    def comment(index):
        if text.startswith("//", index):
            end = text.find("\n", index)
            return len(text) if end < 0 else end
        depth, index = 1, index + 2
        while index < len(text) and depth:
            if text.startswith("/*", index):
                depth += 1
                index += 2
            elif text.startswith("*/", index):
                depth -= 1
                index += 2
            else:
                index += 1
        if depth:
            raise PortError("Unterminated Kotlin block comment")
        return index

    def quoted(index):
        quote = '"""' if text.startswith('"""', index) else text[index]
        index += len(quote)
        while index < len(text):
            if text.startswith(quote, index):
                return index + len(quote)
            if text[index] == "\\" and quote != '"""':
                index += 2
            elif text.startswith("${", index) and quote != "'":
                index = interpolation(index + 2)
            else:
                index += 1
        raise PortError("Unterminated Kotlin literal")

    def interpolation(index):
        depth = 1
        while index < len(text):
            if text.startswith(("//", "/*"), index):
                index = comment(index)
            elif text[index] in "\"'":
                index = quoted(index)
            elif text[index] == "{":
                depth += 1
                index += 1
            elif text[index] == "}":
                depth -= 1
                index += 1
                if not depth:
                    return index
            else:
                index += 1
        raise PortError("Unterminated Kotlin string template")

    lines = [-1] + [match.start() for match in re.finditer("\n", text)]
    result = Syntax.__new__(Syntax)
    result.text, result.tokens, result.pairs = text, [], {}
    index, stack = 0, []
    while index < len(text):
        start = index
        if text[index].isspace():
            index += 1
            continue
        if text.startswith(("//", "/*"), index):
            index = comment(index)
            continue
        if text[index] in "\"'":
            kind = "string" if text[index] == '"' else "character"
            index = quoted(index)
        elif text[index] == "`":
            closing = text.find("`", index + 1)
            if closing < 0 or "\n" in text[index:closing]:
                raise PortError("Unterminated Kotlin escaped identifier")
            index, kind = closing + 1, "identifier"
        elif match := IDENTIFIER.match(text, index):
            index, kind = match.end(), "identifier"
        elif match := NUMBER.match(text, index):
            index, kind = match.end(), "number"
        else:
            operator = next((value for value in ("->", "==", "!=", "<=", ">=", "&&", "||", "?.", "?:", "..")
                             if text.startswith(value, index)), text[index])
            index, kind = index + len(operator), "symbol"
        token = Token(text[start:index], kind, start, index, bisect.bisect_left(lines, start))
        offset = len(result.tokens)
        result.tokens.append(token)
        if kind == "symbol" and token.text in ("(", "[", "{"):
            stack.append(offset)
        elif kind == "symbol" and token.text in (")", "]", "}"):
            if not stack or result.tokens[stack[-1]].text != {")": "(", "]": "[", "}": "{"}[token.text]:
                raise PortError(f"Unbalanced Kotlin delimiter at line {token.line}")
            result.pairs[stack.pop()] = offset
    if stack:
        raise PortError(f"Unclosed Kotlin delimiter at line {result.tokens[stack[-1]].line}")
    return result


def raw(text, tokens, begin, end):
    if begin == end:
        return ""
    return text[tokens[begin].start:tokens[end - 1].end]


def calls(syntax, text, begin, end, *, swift=False):
    tokens, result = syntax.tokens, []
    for index in range(begin, end - 1):
        token = tokens[index]
        if token.kind != "identifier":
            continue
        opening = index + 1
        if tokens[opening].text == "<":
            depth = 1
            opening += 1
            while opening < end and depth:
                depth += (tokens[opening].text == "<") - (tokens[opening].text == ">")
                opening += 1
            if depth:
                continue
        if opening >= end or tokens[opening].text not in ("(", "{"):
            continue
        close = syntax.pairs[opening]
        if close >= end:
            continue
        if tokens[opening].text == "{" and not token.text.startswith("assert"):
            continue
        start = index
        while start > begin + 1 and tokens[start - 1].text in (".", "?.") and tokens[start - 2].kind == "identifier":
            start -= 2
        finish = close + 1
        name = raw(text, tokens, start, index + 1)
        if swift and token.text in ("expect", "require") and index > begin and tokens[index - 1].text == "#":
            name = "#" + name
            start = index - 1
        assertion = (
            name.startswith(("#expect", "#require", "XCTAssert")) or name in ("XCTFail", "Issue.record")
            if swift else token.text.startswith("assert") or token.text in ("fail", "check", "verify", "eventually")
        )
        if tokens[opening].text == "(" and assertion and finish < end and tokens[finish].text == "{":
            finish = syntax.pairs[finish] + 1
        result.append({"name": name, "line": token.line, "expression": raw(text, tokens, start, finish),
                       "assertion_or_checked_wait": assertion})
    return result


def kotlin_methods(syntax, text):
    tokens = syntax.tokens
    package = re.search(r"(?m)^package\s+([\w.]+)", text)
    package = package[1] if package else ""
    classes = []
    for index, token in enumerate(tokens[:-1]):
        if token.text in ("class", "object") and tokens[index + 1].kind == "identifier":
            cursor = index + 2
            while cursor < len(tokens):
                if tokens[cursor].text == "{":
                    classes.append((cursor, syntax.pairs[cursor], tokens[index + 1].text))
                    break
                if tokens[cursor].text in ("fun", "class", "object"):
                    break
                cursor = syntax.pairs.get(cursor, cursor) + 1
    result = []
    for index, token in enumerate(tokens[:-1]):
        if token.text != "fun":
            continue
        opening = index + 1
        while opening < len(tokens) and tokens[opening].text != "(":
            opening += 1
        if opening == len(tokens):
            raise PortError("Missing Kotlin function parameters")
        name = tokens[opening - 1].text.strip("`")
        close = syntax.pairs[opening]
        cursor = close + 1
        while cursor < len(tokens) and tokens[cursor].text not in ("=", "{", "fun", "class", "object"):
            cursor = syntax.pairs.get(cursor, cursor) + 1
        if cursor == len(tokens) or tokens[cursor].text in ("fun", "class", "object"):
            continue
        body = cursor + 1 if tokens[cursor].text == "=" else cursor
        if tokens[cursor].text == "{":
            end = syntax.pairs[cursor] + 1
        else:
            end = body
            line = tokens[body].line
            while end < len(tokens):
                if tokens[end].text in ("fun", "class", "object") or (
                    tokens[end].line > line and tokens[end].text == "@"
                ):
                    break
                if tokens[end].text == "}":
                    break
                end = syntax.pairs.get(end, end) + 1
                if end < len(tokens) and tokens[end].line > tokens[end - 1].line and tokens[end].text not in (".", "?."):
                    break
        owners = [row for row in classes if row[0] < index < row[1]]
        owner = max(owners, default=None, key=lambda row: row[0])
        prefix = text[:token.start].splitlines()[-5:]
        result.append({
            "name": name, "class": package + "." + owner[2] if owner else package,
            "line": token.line, "end_line": tokens[end - 1].line,
            "declaration_token": index,
            "begin_token": body, "end_token": end,
            "body_sha256": text_digest(raw(text, tokens, body, end)),
            "registered": any("@Test" in line for line in prefix),
        })
    return result


def literal(token):
    if token.kind != "string":
        return None
    raw = token.text
    if raw.startswith('"""'):
        body = raw[3:-3]
        return None if re.search(r"\$(?:\{|`|[^\W\d])", body) else body
    body, result, index = raw[1:-1], [], 0
    escapes = {"t": "\t", "b": "\b", "n": "\n", "r": "\r",
               "'": "'", '"': '"', "\\": "\\", "$": "$"}
    while index < len(body):
        char = body[index]
        if char == "$" and re.match(r"(?:\{|`|[^\W\d])", body[index + 1:]):
            return None
        if char != "\\":
            result.append(char)
            index += 1
            continue
        index += 1
        if index >= len(body):
            return None
        escape = body[index]
        if escape == "u":
            digits = body[index + 1:index + 5]
            if len(digits) != 4 or re.fullmatch(r"[0-9A-Fa-f]{4}", digits) is None:
                return None
            result.append(chr(int(digits, 16)))
            index += 5
        elif escape in escapes:
            result.append(escapes[escape])
            index += 1
        else:
            return None
    try:
        return "".join(result).encode("utf-16-le", "surrogatepass").decode("utf-16-le")
    except UnicodeError:
        return None


def named_lambdas(syntax, text, methods):
    tokens, result = syntax.tokens, []
    for method in methods:
        begin, end = method["begin_token"], method["end_token"]
        for index in range(begin, end):
            if tokens[index].kind != "string":
                continue
            name = literal(tokens[index])
            if name is None:
                continue
            original_ids = []
            identity_resolution = None
            opening = None
            cursor = index + 1
            if cursor + 1 < end and tokens[cursor].text == "to" and tokens[cursor + 1].text == "{":
                cursor += 1
            else:
                # A named test-builder call may carry additional signature/input arguments.
                opening = index - 1
                if opening < begin or tokens[opening].text != "(":
                    continue
                cursor = syntax.pairs[opening] + 1
            if cursor >= end or tokens[cursor].text != "{":
                continue
            if opening is not None and index > begin + 1 and tokens[index - 2].text == "original":
                arguments = tokens[opening + 1:syntax.pairs[opening]]
                if len(arguments) not in (3, 5, 7) or any(
                    arguments[position].text != "," for position in range(1, len(arguments), 2)
                ):
                    raise PortError("Malformed suite-qualified original test registration")
                labels = [literal(arguments[position]) for position in range(0, len(arguments), 2)]
                if not labels[0] or not labels[1]:
                    raise PortError("Original test registration needs literal suite and case")
                suite, name = labels[:2]
                if any(label is None for label in labels[2:]):
                    identity_resolution = "nonliteral-signature-or-row-requires-explicit-runner-review"
                else:
                    signature = labels[2] if len(labels) > 2 else "()"
                    row = labels[3] if len(labels) > 3 else ""
                    original_ids = [f"{suite}::{name}{signature}{row}"]
                    identity_resolution = "exact-literal-display"
            close = syntax.pairs[cursor]
            result.append({
                **method, "factory_method": method["name"], "name": name,
                "line": tokens[cursor].line, "end_line": tokens[close].line,
                "begin_token": cursor, "end_token": close + 1,
                "body_sha256": text_digest(raw(text, tokens, cursor, close + 1)),
                "registration": raw(text, tokens, max(begin, index - 2), cursor),
                "explicit_original_case_ids": original_ids,
                "registration_identity_resolution": identity_resolution,
            })
    return result


def annotated_cases(syntax, methods):
    tokens, result = syntax.tokens, defaultdict(list)
    for index in range(len(tokens) - 3):
        if tokens[index].text != "@" or tokens[index + 1].text not in ("OriginalCase", "SourceCase", "SourceCases"):
            continue
        opening = index + 2
        if tokens[opening].text != "(":
            raise PortError("Malformed native original-family annotation")
        closing = syntax.pairs[opening]
        following = [method for method in methods if method["declaration_token"] > closing]
        if not following:
            raise PortError("Original-family annotation has no following function")
        method = min(following, key=lambda value: value["declaration_token"])
        # Do not transport labels out of a previous function into the next test.
        if any(value["begin_token"] <= index < value["end_token"] for value in methods):
            raise PortError("Original-family annotation is nested inside a native function")
        values = [literal(token) for token in tokens[opening + 1:closing] if token.kind == "string"]
        if not values or any(value is None for value in values):
            raise PortError("Original-family annotation needs literal case identities")
        result[method["declaration_token"]].extend(values)
    return result


def native_units(repo):
    by_name, files = defaultdict(list), {}
    for path in sorted((repo / "android").rglob("*.kt")):
        relative = path.relative_to(repo).as_posix()
        if "build" in path.relative_to(repo / "android").parts:
            continue
        text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
        syntax = kotlin_syntax(text)
        methods = kotlin_methods(syntax, text)
        sources = sorted({source for source, pin in audit.SOURCE_HEADER.findall(text) if pin == SOURCE_SHA})
        files[relative] = {"sha256": text_digest(text), "source_references": sources,
                           "methods": [{"name": row["name"], "line": row["line"], "end_line": row["end_line"]}
                                       for row in methods]}
        if "/src/test/" not in relative and "/src/androidTest/" not in relative:
            continue
        annotations = annotated_cases(syntax, methods)
        for unit in methods + named_lambdas(syntax, text, methods):
            operations = calls(syntax, text, unit["begin_token"], unit["end_token"])
            assertions = [row for row in operations if row["assertion_or_checked_wait"]]
            record = {
                "path": relative, "sha256": files[relative]["sha256"], "class": unit["class"],
                "method_or_family_name": unit["name"], "line": unit["line"], "end_line": unit["end_line"],
                "factory_method": unit.get("factory_method"),
                "registration_identity_resolution": unit.get("registration_identity_resolution"),
                "body_sha256": unit["body_sha256"], "registration": unit.get("registration"),
                "assertions": assertions,
                "actions_and_helper_calls": [row for row in operations if not row["assertion_or_checked_wait"]],
                "source_references": sources,
                "explicit_original_case_ids": annotations[unit["declaration_token"]] if "registration" not in unit
                    else unit["explicit_original_case_ids"],
                "execution": "exact-candidate-runner-identity-resolution-required",
            }
            by_name[unit["name"]].append(record)
            for identity in record["explicit_original_case_ids"]:
                by_name[identity].append(record)
    return by_name, files


def proposals(repo=audit.REPO, *, source_maps=None):
    reference = FrozenReference(repo)
    originals = load_json(repo_path(repo, audit.DETAILS))
    original_text = reference.read_many(row["path"] for row in originals["files"])
    native, native_files = native_units(repo)
    groups = defaultdict(lambda: {"sources": [], "families": []})
    if source_maps is None:
        source_maps = load_json(repo_path(repo, audit.OUTPUT))
    for source in source_maps["sources"]:
        owner = source["owner"] or "scope-exclusions"
        groups[owner]["sources"].append({
            **source,
            "native_contracts": [
                {"path": path, **native_files[path]} for path in source["candidate_native_paths"]
            ],
            "review": "compare-observable-actions-state-error-cancellation-and-resources",
        })
    for file in originals["files"]:
        text = original_text[file["path"]]
        syntax = Syntax(text)
        tokens = syntax.tokens
        owner = reference.inventory[file["path"]]["primary_owner"] or "scope-exclusions"
        for case in file["cases"]:
            begin = next(index for index, token in enumerate(tokens) if token.line >= case["line"])
            end = next((index for index in range(begin, len(tokens)) if tokens[index].line > case["end_line"]), len(tokens))
            operations = calls(syntax, text, begin, end, swift=True)
            candidates = joined_candidates(native, case["method"], case["id"])
            # A label/function name is only a join candidate. Preserve all assertion bodies,
            # including helpers and registration, so the reviewer can compare real semantics.
            selected = [row for row in candidates if file["path"] in row["source_references"]]
            if not selected:
                selected = candidates
            groups[owner]["families"].append({
                "source_path": file["path"], "source_blob_sha": file["blob_sha"],
                "case_id": case["id"], "parameter_family": case["parameter_family"],
                "line": case["line"], "end_line": case["end_line"],
                "declaration_sha256": text_digest(raw(text, tokens, begin, end)),
                "inputs": case["inputs"], "compile_conditions": case["compile_conditions"],
                "assertions": [row for row in operations if row["assertion_or_checked_wait"]],
                "helper_assertions": case["helper_assertions"],
                "actions_and_helper_calls": [row for row in operations if not row["assertion_or_checked_wait"]],
                "native_proposals": selected,
                "review": "scope-excluded-not-passed" if reference.inventory[file["path"]]["exclusion"] else
                    "compare-assertion-input-helper-and-consumer-semantics" if selected else "unresolved-native-family",
            })
    return {
        owner: {
            "schema_version": 1, "generator": "android/parity-tests/semantic.py", "owner": owner,
            "source_sha": SOURCE_SHA, "manifest_sha256": reference.manifest_sha256,
            "proposal_not_acceptance": True, **group,
        }
        for owner, group in sorted(groups.items())
    }


def joined_candidates(native, method, identity):
    result = {}
    for row in native.get(method, []) + native.get(identity, []):
        key = (row["path"], row["class"], row["method_or_family_name"],
               row["line"], row["body_sha256"], row["factory_method"])
        result[key] = row
    return list(result.values())


def checked_assertion_helpers(group, repo):
    configured = group.get("checked_assertion_helpers", {})
    if not isinstance(configured, dict) or any(
        not isinstance(name, str) or not IDENTIFIER.fullmatch(name)
        or not isinstance(path, str) or path not in group["helpers"]
        or not path.startswith("android/") or "/src/test/" not in path
        for name, path in configured.items()
    ):
        raise PortError("Malformed or unbound checked assertion helper")
    result = {}
    for name, path in configured.items():
        helper = repo_path(repo, path)
        if not helper.is_file() or helper.is_symlink():
            raise PortError(f"Missing or unsafe checked assertion helper: {path}")
        text = helper.read_text(encoding="utf-8").replace("\r\n", "\n")
        syntax = kotlin_syntax(text)
        methods = [method for method in kotlin_methods(syntax, text) if method["name"] == name]
        if len(methods) != 1:
            raise PortError(f"Missing or ambiguous checked assertion helper definition: {name}")
        method = methods[0]
        assertions = [call for call in calls(syntax, text, method["begin_token"], method["end_token"])
                      if call["assertion_or_checked_wait"]]
        if not assertions:
            raise PortError(f"Checked helper has no actual assertions: {name}")
        result[name] = {
            "path": path, "sha256": text_digest(text), "method": name,
            "line": method["line"], "end_line": method["end_line"],
            "body_sha256": method["body_sha256"], "assertions": assertions,
        }
    return result


def bind_reviews(groups, repo=audit.REPO):
    value = load_json(repo_path(repo, REVIEW_PROPOSALS))
    if value.get("schema_version") != 1 or value.get("source_sha") != SOURCE_SHA:
        raise PortError("Semantic comparison source/schema drift")
    if value.get("status") != "candidate-semantic-comparisons-not-independent-approval":
        raise PortError("Candidate semantic comparisons cannot assert independent approval")
    indexed = {}
    for group in groups.values():
        for family in group["families"]:
            key = (family["source_path"], family["case_id"])
            if key in indexed:
                raise PortError("Ambiguous original semantic family")
            indexed[key] = family
    result, identities, compared = [], set(), set()
    renamed_units = None
    for group in value["groups"]:
        if group["id"] in identities:
            raise PortError("Duplicate semantic comparison group")
        identities.add(group["id"])
        families = [family for (path, _), family in indexed.items() if path == group["source_path"]]
        methods = {family["case_id"].split("::", 1)[1].rsplit("(", 1)[0] for family in families}
        selected = set(group["methods"])
        partial = group.get("partial_source_file", False)
        if type(partial) is not bool or not selected or len(selected) != len(group["methods"]) or \
                len(methods) != len(families) or \
                (not selected < methods if partial else selected != methods):
            raise PortError(f"Missing/duplicate original method or parameter family in comparison: {group['id']}")
        original_family_count = len(families)
        families = [family for family in families
                    if family["case_id"].split("::", 1)[1].rsplit("(", 1)[0] in selected]
        methods = selected
        overrides = group.get("native_method_overrides", {})
        if not overrides.keys() <= methods:
            raise PortError("Native method adaptation names an unknown original")
        targets = group.get("native_target_overrides", {})
        if not isinstance(targets, dict) or not targets.keys() <= methods or any(
            not isinstance(target, dict) or set(target) != {"native_path", "runner_class", "runner_directory"}
            or any(not isinstance(field, str) or not field for field in target.values())
            for target in targets.values()
        ):
            raise PortError("Malformed or unknown original native target adaptation")
        helpers = []
        for path in group["helpers"]:
            helper = repo_path(repo, path)
            if not helper.is_file() or helper.is_symlink():
                raise PortError(f"Missing or unsafe reviewed assertion helper/input: {path}")
            helpers.append({"path": path, "sha256": text_digest(helper.read_text(encoding="utf-8").replace("\r\n", "\n"))})
        checked_helpers = checked_assertion_helpers(group, repo)
        for family in families:
            method = family["case_id"].split("::", 1)[1].rsplit("(", 1)[0]
            native_name = overrides.get(method, method)
            target = targets.get(method, group)
            candidates = [candidate for candidate in family["native_proposals"]
                          if candidate["path"] == target["native_path"] and
                          (family["case_id"] in candidate.get("explicit_original_case_ids", [])
                           if group["runner_name"] == "annotated-method" else candidate["method_or_family_name"] == native_name)
                          and (group["runner_name"] != "original-case-id" or
                               (candidate.get("registration_identity_resolution") !=
                                "nonliteral-signature-or-row-requires-explicit-runner-review" and
                                (not candidate.get("explicit_original_case_ids") or
                                 family["case_id"] in candidate["explicit_original_case_ids"])))
                          and candidate["factory_method"] == group.get("native_factory")]
            if not candidates and native_name != method and group["runner_name"] != "annotated-method":
                # A disclosed rename must be resolved against the actual native body,
                # not silently accepted by the candidate's prose.
                if renamed_units is None:
                    renamed_units, _ = native_units(repo)
                candidates = [candidate for candidate in renamed_units[native_name]
                              if candidate["path"] == target["native_path"] and
                              group["source_path"] in candidate["source_references"] and
                              candidate["factory_method"] == group.get("native_factory")]
            if len(candidates) != 1:
                raise PortError(f"Missing/ambiguous actual native assertions: {group['id']}: {method}")
            native = candidates[0]
            helper_assertions = [
                {"invocation": call, "definition": checked_helpers[call["name"]]}
                for call in native["assertions"] + native.get("actions_and_helper_calls", [])
                if call["name"] in checked_helpers
            ]
            if not native["assertions"] and not helper_assertions:
                raise PortError(f"Missing/ambiguous actual native assertions: {group['id']}: {method}")
            if group["runner_name"] == "original-case-id" and (
                native.get("registration_identity_resolution") ==
                    "nonliteral-signature-or-row-requires-explicit-runner-review" or
                (native.get("explicit_original_case_ids") and
                 family["case_id"] not in native["explicit_original_case_ids"])
            ):
                raise PortError("Unresolved or mismatched original runner display identity")
            if native["class"] != target["runner_class"]:
                raise PortError("Native assertion owner disagrees with expected runner class")
            if group["runner_name"] not in ("method", "method()", "original-case-id", "annotated-method"):
                raise PortError("Unknown native test registration shape")
            runner_name = (native_name + "()" if group["runner_name"] == "method()" else
                           native["method_or_family_name"] if group["runner_name"] in ("method", "annotated-method") else family["case_id"])
            identity = (family["source_path"], family["case_id"])
            if identity in compared:
                raise PortError("Original semantic family compared more than once")
            compared.add(identity)
            result.append({
                "group": group["id"], "comparison_sha256": digest(group),
                "source_file_family_count": original_family_count,
                "group_compared_family_count": len(families),
                "partial_source_file": partial,
                "source_path": family["source_path"], "source_blob_sha": family["source_blob_sha"],
                "case_id": family["case_id"], "parameter_family": family["parameter_family"],
                "original_declaration_sha256": family["declaration_sha256"],
                "original_assertions": family["assertions"], "original_inputs": family["inputs"],
                "native": native, "assertion_helper_inputs": helpers,
                "native_checked_helper_assertions": helper_assertions,
                "runner_directory": target["runner_directory"],
                "runner_identity": [target["runner_class"], runner_name],
                "status": "candidate-semantic-comparison-awaiting-independent-review-and-current-execution",
            })
    if not result:
        raise PortError("Zero source/native semantic comparisons")
    return {
        "schema_version": 1, "generator": "android/parity-tests/semantic.py",
        "source_sha": SOURCE_SHA, "proposal_sha256": digest(value),
        "families": sorted(result, key=lambda row: (row["source_path"], row["case_id"])),
    }


def verify_execution(bindings, android, runner_directories=None):
    from controller.ci_evidence import require_case_identities

    if runner_directories is None:
        selected = None
    elif (
        not isinstance(runner_directories, (list, tuple, set))
        or not runner_directories
        or any(not isinstance(path, str) or not path for path in runner_directories)
    ):
        raise PortError("Selected runner directories must be nonempty paths")
    else:
        selected = set(runner_directories)
    directories = defaultdict(set)
    for family in bindings["families"]:
        directory = family["runner_directory"]
        if selected is None or directory in selected:
            directories[directory].add(tuple(family["runner_identity"]))
    missing = selected - directories.keys() if selected is not None else set()
    if missing:
        raise PortError(f"Selected runner directories have no reviewed identities: {sorted(missing)}")
    if not directories:
        raise PortError("Zero reviewed runner identities for selected directories")
    return {directory: require_case_identities(repo_path(android, directory), identities)
            for directory, identities in sorted(directories.items())}


def generate(check=False, repo=audit.REPO, *, verify=False, runner_directories=None):
    groups = proposals(repo, source_maps=audit.generate(repo) if check else None)
    if not check:
        actual = {path.stem for path in repo_path(repo, GROUP_ROOT).glob("*.json")}
        if actual - groups.keys():
            raise PortError("Unaccounted semantic source group output")
        for owner, value in groups.items():
            write_or_check(repo_path(repo, f"{GROUP_ROOT}/{owner}.json"), json_bytes(value), check=False)
    bindings = bind_reviews(groups, repo)
    if not check:
        write_or_check(repo_path(repo, REVIEW_BINDINGS), json_bytes(bindings), check=False)
    result = {"groups": len(groups),
              "families": sum(len(value["families"]) for value in groups.values()),
              "with_native_assertion_proposals": sum(bool(case["native_proposals"]) for value in groups.values()
                                                    for case in value["families"]),
              "candidate_semantically_compared_families": len(bindings["families"]),
              "scope": "actual source/native assertion/input/action bodies; independent semantic review still required"}
    if verify:
        result["execution"] = verify_execution(bindings, repo / "android", runner_directories)
    return result


def main(argv=None, repo=audit.REPO):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify-execution", action="store_true")
    parser.add_argument("--runner-directory", action="append")
    args = parser.parse_args(argv)
    try:
        if args.runner_directory and not args.verify_execution:
            raise PortError("--runner-directory requires --verify-execution")
        print(json.dumps(generate(
            check=args.check or args.verify_execution,
            repo=repo,
            verify=args.verify_execution,
            runner_directories=args.runner_directory,
        )))
        return 0
    except (PortError, OSError, ValueError, KeyError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
