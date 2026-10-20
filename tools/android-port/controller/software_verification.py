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


def amendment(repo=REPO):
    record = load_json(repo / RECORD_PATH, 64 * 1024)
    if digest(record) != RECORD_SHA256:
        raise PortError("Software verification amendment identity drift")
    return record


def transform(data, *, reverse=False, repo=REPO):
    record = amendment(repo)
    source, target = ("current", "previous") if reverse else ("previous", "current")
    if digest(data) != record[source + "_manifest_sha256"]:
        raise PortError("Software verification requires the complete exact catalog preimage")
    result = copy.deepcopy(data)
    for change in record["work_packages"]:
        found = [wp for wp in result["work_packages"] if wp["id"] == change["id"]]
        if len(found) != 1 or found[0]["verification"] != change[source]:
            raise PortError("Software verification exact delta mismatch")
        found[0]["verification"] = copy.deepcopy(change[target])
    if digest(result) != record[target + "_manifest_sha256"]:
        raise PortError("Software verification changed the catalog outside its exact delta")
    return result


def project(data, repo=REPO):
    if digest(data) == CURRENT_MANIFEST_SHA256:
        return transform(data, reverse=True, repo=repo)
    return data


def apply(data, repo=REPO):
    if digest(data) == PREVIOUS_MANIFEST_SHA256:
        return transform(data, repo=repo)
    return data
