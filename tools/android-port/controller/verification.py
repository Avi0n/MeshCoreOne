"""AndroidOnly: WP-003 Shared declared software verification for Android work packages."""

from .errors import PortError
from .model import Manifest


def require_shared_whole_lane(manifest: Manifest, wp: dict):
    verification = wp["verification"]
    if verification["configured"] or verification["commands"]:
        raise PortError(verification["blocker"] or "Shared whole-WP verification requires an empty entry")
    if (
        "docs/android/test-cases.json" in wp["write_paths"]
        or not any(path.startswith(("android/app/", "android/core/", "android/feature/", "android/platform/"))
                   for path in wp["write_paths"])
    ):
        raise PortError("This non-module or original-inventory WP requires its own verification contract")
    if not any(entry["exclusion"] is None for entry in manifest.inputs(wp["id"], False)):
        raise PortError("This WP has no primary-owned source inputs for the shared verification contract")
    return declared_software_lane(manifest)


def declared_software_lane(manifest: Manifest) -> list[list[str]]:
    ci = "tools/android-port/controller/ci.py"
    required = [
        ["python", ci, "preflight"],
        ["python", ci, "run", "--stage", "scaffold"],
        ["python", ci, "inspect"],
    ]
    verification = manifest.wp("WP-003")["verification"]
    if (
        not isinstance(verification, dict) or verification.get("configured") is not True
        or not isinstance(verification.get("commands"), list)
        or any(command not in verification["commands"] for command in required)
    ):
        raise PortError("The declared project-wide software verification lane is missing or unconfigured")
    return [command.copy() for command in required]
