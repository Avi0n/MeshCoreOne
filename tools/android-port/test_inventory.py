"""AndroidOnly: WP-004 Frozen Swift case/family inventory; no port-acceptance claims."""

import argparse
import math
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from controller.errors import PortError
from oracle.reference import (
    FrozenReference, OracleError, REPO, SOURCE_SHA, exact_fields, json_bytes,
    repo_path, sha256, write_or_check,
)
from oracle.swift import Syntax, assertion_sites, canonical, declarations, literal_string

CATALOG_PATH = "docs/android/test-cases.json"
DETAILS_PATH = "docs/android/evidence/WP-004/inventory-details.json"
DYNAMIC_TYPES = {"OCVPreset": "MC1Services/Sources/MC1Services/Models/OCVPreset.swift"}
POLLING_HELPERS = {
    "MC1Services/Tests/": "MC1Services/Tests/MC1ServicesTests/Helpers/TestPolling.swift",
    "MC1Tests/": "MC1Tests/Helpers/TestPolling.swift",
    "MeshCore/Tests/": "MeshCore/Tests/MeshCoreTests/Helpers/TestPolling.swift",
}
NON_SUITE_TEST_PATHS = {
    "MC1Tests/StoreKitTestAvailability.swift",
    "MC1Tests/ViewModels/GatedMessageTranslator.swift",
    "MC1Tests/Views/Chats/ChatViewModelDependencies+Testing.swift",
    "MC1Tests/Views/Chats/Components/IsolatedIncomingAvatarJPEGStoreTrait.swift",
    "MC1Tests/Views/Chats/Components/MessageBubbleTestData.swift",
    "MC1Tests/Views/Chats/Models/MessageFragmentBuilderFixtures.swift",
    "MC1Tests/Views/Tools/CLI/MockConfigurationSession.swift",
}


def annotation(syntax, attribute):
    begin, end = attribute.arguments
    return {
        "name": attribute.name,
        "line": syntax.tokens[attribute.begin].line,
        "declaration": canonical(syntax.tokens[attribute.begin:attribute.end]),
        "arguments": [canonical(syntax.tokens[a:b]) for a, b in syntax.split(begin, end)],
    }


def display_name(syntax, attributes, name):
    attribute = next((attr for attr in attributes if attr.name.split(".")[-1] == name), None)
    if attribute is None:
        return None
    begin, end = attribute.arguments
    parts = syntax.split(begin, end)
    if parts and parts[0][1] == parts[0][0] + 1 and syntax.tokens[parts[0][0]].kind == "string":
        return literal_string(syntax.tokens[parts[0][0]])
    return None


def enum_cases(syntax, declaration):
    begin, end = declaration.body
    result, index = [], begin
    while index < end:
        token = syntax.tokens[index]
        if token.text == "case":
            cursor = index + 1
            while cursor < end:
                candidate = syntax.tokens[cursor]
                if candidate.kind != "identifier":
                    raise OracleError("Unsupported enum input-family case declaration")
                result.append(candidate.text)
                cursor += 1
                while cursor < end:
                    if syntax.tokens[cursor].text == ",":
                        cursor += 1
                        break
                    if syntax.tokens[cursor].line > syntax.tokens[cursor - 1].line:
                        break
                    cursor = syntax.pairs.get(cursor, cursor) + 1
                else:
                    break
                if cursor >= end or syntax.tokens[cursor - 1].text != ",":
                    break
            index = cursor
        else:
            index = syntax.pairs.get(index, index) + 1
    if not result or len(result) != len(set(result)):
        raise OracleError("Missing/duplicate enum input-family cases")
    return result


def input_axis(syntax, begin, end, declaration, members, dependencies):
    tokens = syntax.tokens[begin:end]
    expression = canonical(tokens)
    definition = None
    if tokens and tokens[0].text == "[" and syntax.pairs[begin] == end - 1:
        rows = [canonical(syntax.tokens[a:b]) for a, b in syntax.split(begin + 1, end - 1)]
        if not rows:
            raise OracleError("Zero-input parameter family")
        return {"expression": expression, "kind": "literal-collection", "declared_rows": rows,
                "declared_count": len(rows), "definition": None}
    if len(tokens) == 1 and tokens[0].kind == "identifier":
        found = [member for member in members if member.kind in ("let", "var")
                 and member.name == tokens[0].text and member.scope in (declaration.scope, ())]
        if len(found) != 1:
            raise OracleError(f"Unknown/ambiguous parameter-family declaration: {expression}")
        member = found[0]
        equals = next((i for i in range(member.keyword, member.end) if syntax.tokens[i].text == "="), None)
        if equals is None:
            raise OracleError(f"Missing parameter-family initializer: {expression}")
        value = input_axis(syntax, equals + 1, member.end, declaration, [], dependencies)
        if value["kind"] != "literal-collection":
            raise OracleError(f"Unsupported indirect parameter family: {expression}")
        definition = {
            "symbol": member.name, "line": syntax.tokens[member.keyword].line,
            "declaration": syntax.raw(member.begin, member.end),
        }
        return {"expression": expression, "kind": "named-collection", "declared_rows": value["declared_rows"],
                "declared_count": value["declared_count"], "definition": definition}
    if len(tokens) >= 3 and tokens[1].text == "." and tokens[2].text == "allCases":
        symbol = tokens[0].text
        if symbol not in dependencies:
            raise OracleError(f"Unknown external input-family definition: {expression}")
        dependency = dependencies[symbol]
        rows = list(dependency["cases"])
        if len(tokens) > 3:
            suffix = [token.text for token in tokens[3:]]
            if len(suffix) != 9 or suffix[:7] != [".", "filter", "{", "$", "0", "!=", "."] or suffix[-1] != "}":
                raise OracleError(f"Unsupported allCases input-family transform: {expression}")
            excluded = suffix[7]
            if excluded not in rows:
                raise OracleError(f"Unknown filtered enum case: {excluded}")
            rows.remove(excluded)
        if not rows:
            raise OracleError("Zero-input enum parameter family")
        return {"expression": expression, "kind": "enum-collection", "declared_rows": rows,
                "declared_count": len(rows), "definition": dependency}
    raise OracleError(f"Unsupported/unknown parameter-family expression: {expression}")


def parameter_family(syntax, declaration, members, dependencies):
    tests = [attr for attr in declaration.attributes if attr.name.split(".")[-1] == "Test"]
    if len(tests) > 1:
        raise OracleError("Duplicate @Test annotation")
    if not tests:
        return "single", None
    attribute = tests[0]
    parts = syntax.split(*attribute.arguments)
    argument_index = None
    for index, (begin, end) in enumerate(parts):
        if end - begin >= 2 and [token.text for token in syntax.tokens[begin:begin + 2]] == ["arguments", ":"]:
            if argument_index is not None:
                raise OracleError("Duplicate arguments: label")
            argument_index = index
    if argument_index is None:
        return "single", None
    axes = []
    for index in range(argument_index, len(parts)):
        begin, end = parts[index]
        if index == argument_index:
            begin += 2
        if begin >= end:
            raise OracleError("Empty arguments: parameter family")
        axes.append(input_axis(syntax, begin, end, declaration, members, dependencies))
    expression = " | ".join(axis["expression"] for axis in axes)
    family = {
        "declaration": syntax.raw(attribute.begin, attribute.end),
        "axes": axes,
        "combination": "cartesian" if len(axes) > 1 else "collection",
        "declared_count": math.prod(axis["declared_count"] for axis in axes),
    }
    return "arguments-sha256:" + sha256(expression.encode("utf-8")), family


def compile_conditions(syntax):
    result, active, index = {}, [], 0
    while index < len(syntax.tokens):
        token = syntax.tokens[index]
        if token.text == "#" and index + 1 < len(syntax.tokens):
            directive = syntax.tokens[index + 1]
            if directive.text in ("if", "elseif", "else", "endif"):
                cursor = index + 2
                while cursor < len(syntax.tokens) and syntax.tokens[cursor].line == token.line:
                    cursor += 1
                value = canonical(syntax.tokens[index:cursor])
                if directive.text == "if":
                    active.append([value])
                elif not active:
                    raise OracleError("Unmatched Swift conditional-compilation directive")
                elif directive.text == "endif":
                    active.pop()
                else:
                    active[-1].append(value)
                index = cursor
                continue
        result[index] = [list(branch) for branch in active]
        index += 1
    if active:
        raise OracleError("Unclosed Swift conditional-compilation directive")
    return result


def parse_file(text: str, *, dependencies=None, external_helpers=None):
    syntax = Syntax(text)
    members = declarations(syntax)
    functions = [member for member in members if member.kind in ("func", "init", "deinit")]
    conditions = compile_conditions(syntax)
    direct, calls = {}, {}
    for index, function in enumerate(functions):
        body = syntax.tokens[slice(*function.body)] if function.body else []
        direct[index] = assertion_sites(body)
        calls[index] = {token.text.strip("`") for offset, token in enumerate(body[:-1])
                        if token.kind == "identifier" and body[offset + 1].text == "("}
    cases, methods, suites = [], [], []
    for member in members:
        if member.kind in ("struct", "class", "enum", "actor", "extension"):
            suites.append({
                "scope": ".".join(member.scope + (member.name,)),
                "kind": member.kind, "line": syntax.tokens[member.keyword].line,
                "display_name": display_name(syntax, member.attributes, "Suite"),
                "annotations": [annotation(syntax, attr) for attr in member.attributes],
                "xctest": member.xctest,
            })
    for index, function in enumerate(functions):
        swift_test = any(attr.name.split(".")[-1] == "Test" for attr in function.attributes)
        modifiers = {token.text for token in syntax.tokens[function.begin:function.keyword]}
        xctest = (function.xctest and function.kind == "func" and function.parent is not None
                  and function.parent.kind == "class" and function.name.startswith("test")
                  and function.parameters[0] == function.parameters[1] and not modifiers & {"static", "class"})
        signature = canonical(syntax.tokens[slice(*function.parameters)]) if function.parameters else ""
        identity = (".".join(function.scope) + "::" if function.scope else "") + function.name + "(" + signature + ")"
        indirect, visited, queue = [], {index}, [index]
        while queue:
            current = queue.pop()
            for called in sorted(calls[current]):
                candidates = [i for i, helper in enumerate(functions) if helper.name == called
                              and helper.scope in (function.scope, ())]
                for target in candidates:
                    if target not in visited:
                        visited.add(target)
                        queue.append(target)
                        if direct[target]:
                            indirect.append({"method": functions[target].name,
                                             "line": syntax.tokens[functions[target].keyword].line,
                                             "assertions": direct[target], "provenance": None})
        body = syntax.tokens[slice(*function.body)] if function.body else []
        for name, helper in (external_helpers or {}).items():
            sites = [token.line for offset, token in enumerate(body)
                     if token.text == name and offset >= 2
                     and [part.text for part in body[offset - 2:offset]] == ["try", "await"]
                     and offset + 1 < len(body) and body[offset + 1].text in ("(", "{")]
            if sites:
                indirect.append({**helper, "invocation_lines": sites})
        method = {
            "id": identity, "method": function.name, "scope": ".".join(function.scope),
            "signature": signature, "line": syntax.tokens[function.keyword].line,
            "end_line": syntax.tokens[function.end - 1].line,
            "direct_assertions": direct[index], "helper_assertions": indirect,
        }
        if swift_test or xctest:
            if function.kind != "func" or function.body is None:
                raise OracleError(f"Test annotation has no executable function body: {identity}")
            if swift_test and xctest:
                raise OracleError(f"Ambiguous double test registration: {identity}")
            family_id, family = parameter_family(syntax, function, members, dependencies or {})
            cases.append({
                **method,
                "framework": "SwiftTesting" if swift_test else "XCTest",
                "display_name": display_name(syntax, function.attributes, "Test"),
                "annotations": [annotation(syntax, attr) for attr in function.attributes],
                "compile_conditions": conditions.get(function.keyword, []),
                "parameter_family": family_id, "inputs": family,
                "assertion_status": "direct" if direct[index] else "helper" if indirect else "none",
                "port_status": "pending",
            })
        else:
            methods.append(method)
    annotated = sum(
        token.text == "@" and (
            syntax.tokens[index + 1].text == "Test"
            or [part.text for part in syntax.tokens[index + 1:index + 4]] == ["Testing", ".", "Test"]
        )
        for index, token in enumerate(syntax.tokens[:-1])
    )
    if annotated != sum(case["framework"] == "SwiftTesting" for case in cases):
        raise OracleError("Unaccounted @Test annotation; unsupported/nested test declaration")
    return {"suites": suites, "cases": cases, "non_test_methods": methods,
            "file_assertions": assertion_sites(syntax.tokens)}


def inventory_counts(files):
    cases = [case for file in files for case in file["cases"]]
    return {
        "paths": len(files), "test_paths": sum(file["kind"] == "test" for file in files),
        "support_paths": sum(file["kind"] == "support" for file in files),
        "case_declarations": len(cases),
        "parameterized_declarations": sum(case["inputs"] is not None for case in cases),
        "declared_parameter_rows": sum(case["inputs"]["declared_count"] for case in cases if case["inputs"]),
        "direct_assertion_cases": sum(case["assertion_status"] == "direct" for case in cases),
        "helper_assertion_cases": sum(case["assertion_status"] == "helper" for case in cases),
        "no_assertion_cases": sum(case["assertion_status"] == "none" for case in cases),
        "non_test_methods": sum(len(file["non_test_methods"]) for file in files),
        "ported_cases": 0,
    }


def validate_outputs(catalog, details, originals):
    exact_fields(catalog, {"schema_version", "source_sha", "entries"}, "controller case catalog")
    exact_fields(details, {"schema_version", "source_sha", "generator", "counts", "files"}, "inventory details")
    if catalog["schema_version"] != 1 or details["schema_version"] != 1 or catalog["source_sha"] != SOURCE_SHA or details["source_sha"] != SOURCE_SHA:
        raise OracleError("Stale/unsupported case inventory schema or source")
    if not isinstance(catalog["entries"], list) or not isinstance(details["files"], list):
        raise OracleError("Malformed inventory lists")
    expected = {entry["path"]: entry for entry in originals}
    registered, detailed = {}, {}
    for entry in catalog["entries"]:
        exact_fields(entry, {"path", "blob_sha", "has_assertions", "cases"}, "original-case entry")
        path = entry["path"]
        if path in registered or path not in expected or entry["blob_sha"] != expected[path]["blob_sha"]:
            raise OracleError(f"Duplicate, unknown or stale original path/blob: {path}")
        if type(entry["has_assertions"]) is not bool or not isinstance(entry["cases"], list):
            raise OracleError("Malformed case/assertion declaration")
        if (entry["has_assertions"] or (expected[path]["kind"] == "test" and path not in NON_SUITE_TEST_PATHS)) and not entry["cases"]:
            raise OracleError(f"Zero-test original inventory: {path}")
        if path in NON_SUITE_TEST_PATHS and entry["cases"]:
            raise OracleError(f"Frozen non-suite classification drift: {path}")
        identities = set()
        for case in entry["cases"]:
            exact_fields(case, {"id", "parameter_family"}, "original case")
            if any(not isinstance(case[key], str) or not case[key] for key in ("id", "parameter_family")):
                raise OracleError("Empty/invalid original case or parameter family")
            identity = (case["id"], case["parameter_family"])
            if identity in identities:
                raise OracleError("Duplicate original case/parameter family")
            identities.add(identity)
        registered[path] = entry
    for file in details["files"]:
        exact_fields(file, {"path", "source_sha", "blob_sha", "utf8_bytes", "sha256", "kind", "work_package",
                           "exclusion", "role", "suites", "cases", "non_test_methods", "file_assertions"}, "detailed source entry")
        path = file["path"]
        if path in detailed or path not in expected or file["blob_sha"] != expected[path]["blob_sha"] or file["source_sha"] != SOURCE_SHA:
            raise OracleError("Missing/duplicate/stale detailed source provenance")
        if type(file["utf8_bytes"]) is not int or file["utf8_bytes"] < 1 or file["work_package"] != expected[path]["primary_owner"] or file["kind"] != expected[path]["kind"]:
            raise OracleError("Invalid detailed source ownership/byte count")
        identities = []
        for case in file["cases"]:
            exact_fields(case, {"id", "method", "scope", "signature", "line", "end_line", "direct_assertions",
                               "helper_assertions", "framework", "display_name", "annotations", "compile_conditions",
                               "parameter_family", "inputs", "assertion_status", "port_status"}, "detailed case")
            if case["port_status"] != "pending" or case["assertion_status"] not in ("direct", "helper", "none"):
                raise OracleError("Raw inventory cannot claim ported/passed behavior")
            if type(case["line"]) is not int or case["line"] < 1 or case["end_line"] < case["line"]:
                raise OracleError("Invalid original case line provenance")
            status = "direct" if case["direct_assertions"] else "helper" if case["helper_assertions"] else "none"
            if case["assertion_status"] != status:
                raise OracleError("Invalid case assertion classification")
            for helper in case["helper_assertions"]:
                fields = {"method", "line", "assertions", "provenance"}
                if helper.get("provenance") is not None:
                    fields.add("invocation_lines")
                exact_fields(helper, fields, "assertion helper")
                if not helper["assertions"] or type(helper["line"]) is not int or helper["line"] < 1:
                    raise OracleError("Missing assertion-helper definition")
                if helper["provenance"] is not None:
                    exact_fields(helper["provenance"], {"path", "source_sha", "blob_sha", "utf8_bytes", "sha256"}, "external assertion-helper provenance")
                    if helper["provenance"]["source_sha"] != SOURCE_SHA or not helper["invocation_lines"]:
                        raise OracleError("Stale/uninvoked external assertion helper")
            family = case["inputs"]
            if family is None:
                if case["parameter_family"] != "single":
                    raise OracleError("Missing declared parameter family")
            else:
                exact_fields(family, {"declaration", "axes", "combination", "declared_count"}, "parameter family")
                if not isinstance(family["axes"], list) or not family["axes"]:
                    raise OracleError("Missing parameter axes")
                for axis in family["axes"]:
                    exact_fields(axis, {"expression", "kind", "declared_rows", "declared_count", "definition"}, "parameter axis")
                    if not isinstance(axis["expression"], str) or not axis["expression"] or not axis["declared_rows"]:
                        raise OracleError("Missing declared parameter inputs")
                    if type(axis["declared_count"]) is not int or axis["declared_count"] != len(axis["declared_rows"]):
                        raise OracleError("Parameter input count mismatch")
                expression = " | ".join(axis["expression"] for axis in family["axes"])
                if (case["parameter_family"] != "arguments-sha256:" + sha256(expression.encode("utf-8"))
                        or family["declared_count"] != math.prod(axis["declared_count"] for axis in family["axes"])):
                    raise OracleError("Stale parameter-family identity/count")
            identities.append({"id": case["id"], "parameter_family": case["parameter_family"]})
        entry = registered.get(path)
        if entry is None or entry["cases"] != identities:
            raise OracleError("Catalog/detail case or family mismatch")
        has_assertions = any(case["direct_assertions"] or case["helper_assertions"] for case in file["cases"])
        if entry["has_assertions"] != has_assertions:
            raise OracleError("Catalog/detail assertion classification mismatch")
        detailed[path] = file
    if registered.keys() != expected.keys() or detailed.keys() != expected.keys():
        raise OracleError("Missing original test/support paths")
    if not sum(len(file["cases"]) for file in details["files"]):
        raise OracleError("Zero discovered original cases")
    if details["counts"] != inventory_counts(details["files"]):
        raise OracleError("Inventory totals do not match actual declarations")


def generate(reference: FrozenReference):
    originals = reference.test_entries()
    from controller.scope_amendment import is_translation_scope, project_translation_scope

    if is_translation_scope(reference.manifest):
        historical = {
            entry["path"]: entry
            for entry in project_translation_scope(reference.manifest, reference.repo)["inventory"]
        }
        # Retain WP-004 declaration evidence; current scope lives in manifest/not-ported, not old results.
        originals = [historical[entry["path"]] for entry in originals]
    sources = reference.read_many([entry["path"] for entry in originals] + list(DYNAMIC_TYPES.values()))
    dependencies = {}
    assertion_helpers = {}
    for prefix, path in POLLING_HELPERS.items():
        syntax = Syntax(sources[path])
        functions = [member for member in declarations(syntax) if member.kind == "func" and member.name == "waitUntil"]
        if len(functions) != 1 or functions[0].body is None:
            raise OracleError("Missing pinned eventual-condition assertion helper")
        function = functions[0]
        throw_sites = [token.line for index, token in enumerate(syntax.tokens)
                       if token.text == "throw" and index + 1 < len(syntax.tokens)
                       and syntax.tokens[index + 1].text == "WaitTimeoutError"]
        if len(throw_sites) != 1:
            raise OracleError("Unknown pinned polling assertion/failure semantics")
        assertion_helpers[prefix] = {"waitUntil": {
            "method": "waitUntil", "line": syntax.tokens[function.keyword].line,
            "assertions": [{"kind": "eventual-condition/WaitTimeoutError", "line": throw_sites[0]}],
            "provenance": reference.provenance(path, sources[path]),
        }}
    for symbol, path in DYNAMIC_TYPES.items():
        syntax = Syntax(sources[path])
        types = [member for member in declarations(syntax) if member.kind == "enum" and member.name == symbol]
        if len(types) != 1:
            raise OracleError(f"Unknown/ambiguous declared input-family type: {symbol}")
        dependencies[symbol] = {
            **reference.provenance(path, sources[path]), "symbol": symbol,
            "line": syntax.tokens[types[0].keyword].line, "cases": enum_cases(syntax, types[0]),
        }
    catalog = {"schema_version": 1, "source_sha": SOURCE_SHA, "entries": []}
    details = {"schema_version": 1, "source_sha": SOURCE_SHA,
               "generator": "tools/android-port/test_inventory.py", "counts": {}, "files": []}
    for original in originals:
        path, text = original["path"], sources[original["path"]]
        try:
            helpers = next((value for prefix, value in assertion_helpers.items() if path.startswith(prefix)), {})
            parsed = parse_file(text, dependencies=dependencies, external_helpers=helpers)
        except OracleError as error:
            raise OracleError(f"{path}: {error}") from error
        has_assertions = any(case["direct_assertions"] or case["helper_assertions"] for case in parsed["cases"])
        catalog["entries"].append({
            "path": path, "blob_sha": original["blob_sha"], "has_assertions": has_assertions,
            "cases": [{"id": case["id"], "parameter_family": case["parameter_family"]} for case in parsed["cases"]],
        })
        details["files"].append({
            **reference.provenance(path, text), "kind": original["kind"],
            "work_package": original["primary_owner"], "exclusion": original["exclusion"],
            "role": "test-suite" if parsed["cases"] else "assertion-support" if parsed["file_assertions"] else "support-only",
            **parsed,
        })
    details["counts"] = inventory_counts(details["files"])
    validate_outputs(catalog, details, originals)
    return catalog, details


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--regenerate", action="store_true")
    parser.add_argument("--repo", type=Path, default=REPO)
    parser.add_argument("--source-sha", default=SOURCE_SHA)
    args = parser.parse_args(argv)
    try:
        reference = FrozenReference(args.repo, args.source_sha)
        catalog, details = generate(reference)
        write_or_check(repo_path(args.repo, CATALOG_PATH), json_bytes(catalog), check=args.check)
        write_or_check(repo_path(args.repo, DETAILS_PATH), json_bytes(details), check=args.check)
        print(json_bytes({"result": "checked" if args.check else "regenerated", **details["counts"],
                          "scope": "pinned declarations and input families; not executed/ported feature tests"}).decode(), end="")
        return 0
    except (PortError, OSError, ValueError, KeyError) as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
