"""AndroidOnly: WP-501 Source-backed assertion/input/action proposals, never name-based equivalence."""

from collections import defaultdict
import bisect
import hashlib
import json
from pathlib import Path
import re

import audit
from controller.errors import PortError
from controller.schema import digest, load_json
from oracle.reference import FrozenReference, SOURCE_SHA, json_bytes, repo_path, write_or_check
from oracle.swift import IDENTIFIER, NUMBER, Syntax, Token, literal_string

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
    try:
        return literal_string(token)
    except PortError:
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
            close = syntax.pairs[cursor]
            result.append({
                **method, "factory_method": method["name"], "name": name,
                "line": tokens[cursor].line, "end_line": tokens[close].line,
                "begin_token": cursor, "end_token": close + 1,
                "body_sha256": text_digest(raw(text, tokens, cursor, close + 1)),
                "registration": raw(text, tokens, max(begin, index - 2), cursor),
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
                "body_sha256": unit["body_sha256"], "registration": unit.get("registration"),
                "assertions": assertions,
                "actions_and_helper_calls": [row for row in operations if not row["assertion_or_checked_wait"]],
                "source_references": sources,
                "explicit_original_case_ids": annotations[unit["declaration_token"]] if "registration" not in unit else [],
                "execution": "exact-candidate-runner-identity-resolution-required",
            }
            by_name[unit["name"]].append(record)
            if "registration" not in unit:
                for identity in annotations[unit["declaration_token"]]:
                    by_name[identity].append(record)
    return by_name, files


def proposals(repo=audit.REPO):
    reference = FrozenReference(repo)
    originals = load_json(repo_path(repo, audit.DETAILS))
    original_text = reference.read_many(row["path"] for row in originals["files"])
    native, native_files = native_units(repo)
    groups = defaultdict(lambda: {"sources": [], "families": []})
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
    result, identities = [], set()
    for group in value["groups"]:
        if group["id"] in identities:
            raise PortError("Duplicate semantic comparison group")
        identities.add(group["id"])
        families = [family for (path, _), family in indexed.items() if path == group["source_path"]]
        methods = {family["case_id"].split("::", 1)[1].rsplit("(", 1)[0] for family in families}
        if len(methods) != len(families) or set(group["methods"]) != methods or len(group["methods"]) != len(methods):
            raise PortError(f"Missing/duplicate original method or parameter family in comparison: {group['id']}")
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
        for family in families:
            method = family["case_id"].split("::", 1)[1].rsplit("(", 1)[0]
            native_name = overrides.get(method, method)
            target = targets.get(method, group)
            candidates = [candidate for candidate in family["native_proposals"]
                          if candidate["path"] == target["native_path"] and
                          (family["case_id"] in candidate.get("explicit_original_case_ids", [])
                           if group["runner_name"] == "annotated-method" else candidate["method_or_family_name"] == native_name)
                          and candidate["factory_method"] == group.get("native_factory")]
            if not candidates and native_name != method:
                # A disclosed rename must be resolved against the actual native body,
                # not silently accepted by the candidate's prose.
                native, _ = native_units(repo)
                candidates = [candidate for candidate in native[native_name]
                              if candidate["path"] == target["native_path"] and
                              group["source_path"] in candidate["source_references"]]
            if len(candidates) != 1 or not candidates[0]["assertions"]:
                raise PortError(f"Missing/ambiguous actual native assertions: {group['id']}: {method}")
            native = candidates[0]
            if native["class"] != target["runner_class"]:
                raise PortError("Native assertion owner disagrees with expected runner class")
            if group["runner_name"] not in ("method()", "original-case-id", "annotated-method"):
                raise PortError("Unknown native test registration shape")
            runner_name = (native_name + "()" if group["runner_name"] == "method()" else
                           native["method_or_family_name"] if group["runner_name"] == "annotated-method" else family["case_id"])
            result.append({
                "group": group["id"], "comparison_sha256": digest(group),
                "source_path": family["source_path"], "source_blob_sha": family["source_blob_sha"],
                "case_id": family["case_id"], "parameter_family": family["parameter_family"],
                "original_declaration_sha256": family["declaration_sha256"],
                "original_assertions": family["assertions"], "original_inputs": family["inputs"],
                "native": native, "assertion_helper_inputs": helpers,
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


def verify_execution(bindings, android):
    from controller.ci_evidence import require_case_identities

    directories = defaultdict(set)
    for family in bindings["families"]:
        directories[family["runner_directory"]].add(tuple(family["runner_identity"]))
    if not directories:
        raise PortError("Zero reviewed runner identities")
    return {directory: require_case_identities(repo_path(android, directory), identities)
            for directory, identities in sorted(directories.items())}


def generate(check=False, repo=audit.REPO):
    groups = proposals(repo)
    actual = {path.stem for path in repo_path(repo, GROUP_ROOT).glob("*.json")}
    if actual - groups.keys():
        raise PortError("Unaccounted semantic source group output")
    for owner, value in groups.items():
        write_or_check(repo_path(repo, f"{GROUP_ROOT}/{owner}.json"), json_bytes(value), check=check)
    bindings = bind_reviews(groups, repo)
    write_or_check(repo_path(repo, REVIEW_BINDINGS), json_bytes(bindings), check=check)
    return {"groups": len(groups),
            "families": sum(len(value["families"]) for value in groups.values()),
            "with_native_assertion_proposals": sum(bool(case["native_proposals"]) for value in groups.values()
                                                  for case in value["families"]),
            "candidate_semantically_compared_families": len(bindings["families"]),
            "scope": "actual source/native assertion/input/action bodies; independent semantic review still required"}


if __name__ == "__main__":
    import argparse
    import sys

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify-execution", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(generate(check=args.check)))
        if args.verify_execution:
            print(json.dumps(verify_execution(load_json(repo_path(audit.REPO, REVIEW_BINDINGS)), audit.REPO / "android")))
    except (PortError, OSError, ValueError, KeyError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        sys.exit(2)
