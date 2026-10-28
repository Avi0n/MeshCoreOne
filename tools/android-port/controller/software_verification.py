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


def current_amendment(repo=REPO):
    return next_amendment(repo)


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


def project(data, repo=REPO):
    if digest(data) == NEXT_MANIFEST_SHA256:
        data = transform_next(data, reverse=True, repo=repo)
    if digest(data) == CURRENT_MANIFEST_SHA256:
        return transform(data, reverse=True, repo=repo)
    return data


def apply(data, repo=REPO):
    if digest(data) == PREVIOUS_MANIFEST_SHA256:
        data = transform(data, repo=repo)
    if digest(data) == CURRENT_MANIFEST_SHA256:
        return transform_next(data, repo=repo)
    return data
