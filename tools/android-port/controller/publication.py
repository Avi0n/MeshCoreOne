"""AndroidOnly: WP-003 Bounded publisher encoding, not a candidate PASS authority."""

import base64
import binascii
import json
import zlib

from .errors import PortError
from .gates import Binding
from .schema import decode_json, fields

GATE_MARKER = "<!-- android-port-gate-bundle-v1 -->\n"
CHECK_OUTPUT_LIMIT = 65535
STRUCTURED_INPUT_LIMIT = 16 * 1024 * 1024
BUNDLE_FIELDS = {"schema_version", "binding", "evidence", "review", "approvals", "catalog"}


def bounded_output(value: str):
    if not isinstance(value, str) or len(value) > CHECK_OUTPUT_LIMIT or len(value.encode("utf-8")) > CHECK_OUTPUT_LIMIT:
        raise PortError("Checks output exceeds the complete 65,535-character/UTF-8-byte publication bound")
    return value


def encode_structured_body(value, marker: str, compressed_marker: str):
    raw = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    if len(raw) > STRUCTURED_INPUT_LIMIT:
        raise PortError("Structured receipt exceeds the expanded input bound")
    plain = marker + raw.decode("utf-8")
    if len(plain.encode("utf-8")) <= CHECK_OUTPUT_LIMIT:
        return bounded_output(plain)
    encoded = base64.b64encode(zlib.compress(raw, 9)).decode("ascii")
    return bounded_output(compressed_marker + encoded)


def decode_structured_body(body: str, marker: str, compressed_marker: str):
    bounded_output(body)
    if body.startswith(marker):
        return decode_json(body[len(marker):])
    if not body.startswith(compressed_marker):
        raise PortError("Missing structured receipt marker")
    try:
        compressed = base64.b64decode(body[len(compressed_marker):], validate=True)
        decoder = zlib.decompressobj()
        raw = decoder.decompress(compressed, STRUCTURED_INPUT_LIMIT + 1)
        if len(raw) > STRUCTURED_INPUT_LIMIT or not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
            raise PortError("Compressed receipt exceeds its bound or is incomplete")
        return decode_json(raw.decode("utf-8"))
    except (binascii.Error, zlib.error, UnicodeError) as error:
        raise PortError("Malformed compressed structured receipt") from error


def validate_bundle_shape(payload: dict):
    fields(payload, BUNDLE_FIELDS, label="trusted gate bundle")
    if type(payload["schema_version"]) is not int or payload["schema_version"] != 1:
        raise PortError("Unsupported trusted gate bundle schema")
    Binding.parse(payload["binding"])
    if not isinstance(payload["evidence"], dict) or not isinstance(payload["review"], dict):
        raise PortError("Gate evidence/review must be complete objects")
    if not isinstance(payload["approvals"], list) or payload["catalog"] is not None and not isinstance(payload["catalog"], dict):
        raise PortError("Malformed complete approvals/catalog")
    return payload


def encode_bundle(payload: dict):
    validate_bundle_shape(payload)
    # The full catalog is retained. Overflow requires a protected reader amendment, not truncation.
    return bounded_output(GATE_MARKER + json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False))


def decode_bundle(summary: str):
    bounded_output(summary)
    if not summary.startswith(GATE_MARKER):
        raise PortError("Missing structured trusted gate bundle")
    return validate_bundle_shape(decode_json(summary[len(GATE_MARKER):]))


def prepare_output(payload: dict, *, text=""):
    return {
        "title": "Android port exact-bound gate evidence",
        "summary": encode_bundle(payload),
        "text": bounded_output(text),
    }


def require_publisher(policy: dict, authenticated_app_id=None, *, trusted_default_branch=False):
    expected = policy["trusted_gate_publisher_app_id"]
    if (
        type(expected) is not int or expected < 1 or authenticated_app_id != expected
        or not trusted_default_branch
    ):
        raise PortError("BLOCKED: independent authenticated default-branch gate publisher is unconfigured/unproven")
    if not policy["trusted_check_app_ids"] or not policy["trusted_workflow_ids"] or not policy["branch_rules_proven"]:
        raise PortError("BLOCKED: genuine required-check identities/no-bypass branch rules are unproven")
    return expected
