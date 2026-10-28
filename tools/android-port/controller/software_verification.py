"""AndroidOnly: WP-003 Exact software-command amendment, never whole-WP acceptance."""

import copy
from pathlib import Path

from .errors import PortError
from .schema import digest, load_json

REPO = Path(__file__).resolve().parents[3]
RECORD_PATH = "docs/android/evidence/WP-003/software-verification-manifest-amendment.json"
RECORD_SHA256 = "452a509bc1f9a9f405cc8d1cdf68aa696a58c55b0b3717d5a05d6cc70c019bfa"
PREVIOUS_MANIFEST_SHA256 = "39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda"
CURRENT_MANIFEST_SHA256 = "977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8"
NEXT_RECORD_PATH = "docs/android/evidence/WP-003/software-verification-manifest-amendment-v2.json"
NEXT_RECORD_SHA256 = "86352484abcb9426673f5aff61cdf0d9a231335c0534fc410d24df65cf1598da"
NEXT_MANIFEST_SHA256 = "11120a20d04408b3d21fac09180412df0490cabf1dafb8a3c98fe83e0fad31e4"
THIRD_RECORD_PATH = "docs/android/evidence/WP-003/software-verification-manifest-amendment-v3.json"
THIRD_RECORD_SHA256 = "9da7ea7e84b0516c532571f06c4a8519c25e63e498c06cc89ba0fb2ed486b889"
THIRD_MANIFEST_SHA256 = "625c5fa1731138889a5fee131f27f667577b816563d796936557d06534eae5b8"
CONCERN_BINDING_PATH = "docs/android/evidence/WP-003/wp210-concern-family-binding.json"
CONCERN_BINDING_SHA256 = "12dff6ac3a7fceb00d1a34d256581fdb890814183c90d0d9f99a87268c7dd023"


def amendment(repo=REPO):
    record = load_json(repo / RECORD_PATH, 64 * 1024)
    if digest(record) != RECORD_SHA256:
        raise PortError("Software verification amendment identity drift")
    return record


def next_amendment(repo=REPO):
    record = load_json(repo / NEXT_RECORD_PATH, 64 * 1024)
    if digest(record) != NEXT_RECORD_SHA256:
        raise PortError("Software verification v2 amendment identity drift")
    return record


def third_amendment(repo=REPO):
    record = load_json(repo / THIRD_RECORD_PATH, 64 * 1024)
    if digest(record) != THIRD_RECORD_SHA256:
        raise PortError("Software verification v3 amendment identity drift")
    if record.get("concern_family_binding") != {
        "path": CONCERN_BINDING_PATH, "sha256": CONCERN_BINDING_SHA256,
    }:
        raise PortError("Software verification v3 concern-family binding reference drift")
    concern_family_binding(repo)
    return record


def concern_family_binding(repo=REPO):
    binding = load_json(repo / CONCERN_BINDING_PATH, 64 * 1024)
    if digest(binding) != CONCERN_BINDING_SHA256:
        raise PortError("WP-210 concern-family binding identity drift")
    return binding


def current_amendment(repo=REPO):
    return third_amendment(repo)


def _transform(data, record, *, reverse, label):
    source, target = ("current", "previous") if reverse else ("previous", "current")
    if digest(data) != record[source + "_manifest_sha256"]:
        raise PortError(f"{label} requires the complete exact catalog preimage")
    result = copy.deepcopy(data)
    for change in record["work_packages"]:
        found = [wp for wp in result["work_packages"] if wp["id"] == change["id"]]
        if len(found) != 1 or found[0]["verification"] != change[source]:
            raise PortError(f"{label} exact delta mismatch")
        found[0]["verification"] = copy.deepcopy(change[target])
    if digest(result) != record[target + "_manifest_sha256"]:
        raise PortError(f"{label} changed the catalog outside its exact delta")
    return result


def transform(data, *, reverse=False, repo=REPO):
    return _transform(data, amendment(repo), reverse=reverse, label="Software verification")


def transform_next(data, *, reverse=False, repo=REPO):
    return _transform(data, next_amendment(repo), reverse=reverse, label="Software verification v2")


def transform_third(data, *, reverse=False, repo=REPO):
    return _transform(data, third_amendment(repo), reverse=reverse, label="Software verification v3")


def project(data, repo=REPO):
    if digest(data) == THIRD_MANIFEST_SHA256:
        data = transform_third(data, reverse=True, repo=repo)
    if digest(data) == NEXT_MANIFEST_SHA256:
        data = transform_next(data, reverse=True, repo=repo)
    if digest(data) == CURRENT_MANIFEST_SHA256:
        return transform(data, reverse=True, repo=repo)
    return data


def apply(data, repo=REPO):
    if digest(data) == PREVIOUS_MANIFEST_SHA256:
        data = transform(data, repo=repo)
    if digest(data) == CURRENT_MANIFEST_SHA256:
        data = transform_next(data, repo=repo)
    if digest(data) == NEXT_MANIFEST_SHA256:
        return transform_third(data, repo=repo)
    return data
