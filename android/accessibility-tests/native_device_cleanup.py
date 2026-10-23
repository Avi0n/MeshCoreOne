"""AndroidOnly: WP-503 Observe removal of the owned adb transport after emulator exit."""
import argparse
import subprocess
import time


def wait_for_transport_exit(adb: str, serial: str) -> dict:
    start = time.monotonic()
    previous = None
    observations = 0
    while True:
        remaining = 10.0 - (time.monotonic() - start)
        if remaining <= 0:
            raise TimeoutError(f"Owned transport {serial} remained registered after emulator exit for10s")
        result = subprocess.run(
            [adb, "devices"], capture_output=True, text=True,
            check=True, timeout=min(5.0, remaining),
        )
        lines = result.stdout.strip().splitlines()
        if not lines or lines[0] != "List of devices attached":
            raise ValueError("adb devices did not return its expected transport-list header")
        owned = [line for line in lines[1:] if line.split() and line.split()[0] == serial]
        if len(owned) > 1:
            raise ValueError(f"adb reported duplicate owned transport {serial}")
        observations += 1
        elapsed = time.monotonic() - start
        state = owned[0] if owned else "absent"
        if state != previous:
            print(f"WP503_NATIVE_TRANSPORT_EXIT|serial={serial}|state={state}"
                  f"|observations={observations}|elapsedMs={elapsed * 1000:.3f}", flush=True)
            previous = state
        if not owned and elapsed <= 10.0:
            return {"serial": serial, "observations": observations,
                    "elapsed_ms": round(elapsed * 1000, 3), "absent": True}
        time.sleep(min(0.1, max(0.0, 10.0 - elapsed)))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    arguments = parser.parse_args()
    wait_for_transport_exit(arguments.adb, arguments.serial)
