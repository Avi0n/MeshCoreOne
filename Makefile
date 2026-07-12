# Makefile: local developer shortcuts.
# Release and CI automation (certs, TestFlight, App Store) live in fastlane/Fastfile.

# The pipefail wiring below relies on .SHELLFLAGS, which GNU Make honors only at 3.82+. Stock
# macOS /usr/bin/make is 3.81 and ignores .SHELLFLAGS silently, which would let a failed
# xcodebuild exit through xcsift's 0 status undetected. Refuse to run on such a make rather than
# build without the guard; install a newer make (Homebrew `make`) and put it ahead on PATH.
MIN_MAKE_VERSION := 3.82
ifneq ($(MIN_MAKE_VERSION),$(firstword $(sort $(MAKE_VERSION) $(MIN_MAKE_VERSION))))
$(error GNU Make $(MIN_MAKE_VERSION)+ required for pipefail support; found $(MAKE_VERSION). Install a newer make, e.g. Homebrew make)
endif

# `pipefail` makes a `cmd | xcsift` recipe exit with xcodebuild's status, not xcsift's, so a
# failed build/test isn't masked when xcsift's summary exits 0. Requires bash, not /bin/sh.
SHELL := /bin/bash
.SHELLFLAGS := -o pipefail -c

SCHEME  := MC1
PROJECT := MC1.xcodeproj

# The full app suite runs on the project-standard iOS 26 simulator. Override SIM to retarget.
SIM ?= platform=iOS Simulator,name=iPhone 17e,OS=26.5

# StoreKit (SKTestSession) suites must run on an iOS 18.x simulator. Under `xcodebuild
# test`, iOS 26.x simulators deliver 0 products to storekitd (Apple regression
# FB22237318 / FB22774836), so every product-dependent test falsely fails. A method-level
# `-only-testing` selector also silently runs 0 tests for Swift Testing suites, so the target
# below pins iOS 18.x and uses suite-level (type-level) filters. These same suites gate on
# `StoreKitTestAvailability.servesProducts`, so they auto-skip on iOS 26.x in the default
# run; `test-store` is how you actually exercise them. Override STORE_SIM if your machine
# has a different iOS 18.x simulator.
STORE_SIM ?= platform=iOS Simulator,name=iPhone 16e,OS=18.6

# Every suite that constructs an SKTestSession. Keep in sync with the `.enabled(if:)`
# gates in MC1Tests; a suite missing here is never exercised on a products-serving runtime.
STORE_SUITES := \
	-only-testing:MC1Tests/StoreServiceTests \
	-only-testing:MC1Tests/StoreStatePurchaseTests \
	-only-testing:MC1Tests/AppearanceSelectionTests \
	-only-testing:MC1Tests/RefundLinkSectionTests \
	-only-testing:MC1Tests/ThemeServiceOwnershipTests

# Concurrent `xcodebuild test` runs against the same simulator fight over its single
# test-manager connection and hang ("test runner hung before establishing connection"), the
# usual cause of a stuck run when several agent/worktree sessions share one Mac. SIM_LOCK takes
# a per-simulator host lock so same-sim runs serialize while runs on different sims still
# proceed in parallel. The lock is an atomic O_EXCL create (shell noclobber); if the holding
# process is gone (killed run) the stale lock is reclaimed after `ps -p` confirms it is dead,
# and it is released on exit. Skipped under $CI; runners are single-tenant. macOS `shlock` is
# deliberately avoided here: it does not reclaim a dead-owner lock, so a SIGKILLed run would
# wedge every later run. The calling recipe sets `dest` first.
SIM_LOCK = if [ -z "$$CI" ]; then \
		lock="/tmp/mc1-xcodebuild-$$(printf '%s' "$$dest" | tr -c 'A-Za-z0-9' '-').lock"; \
		while :; do \
			if ( set -C; echo $$$$ > "$$lock" ) 2>/dev/null; then break; fi; \
			owner=$$(cat "$$lock" 2>/dev/null); \
			if [ -z "$$owner" ] || ! ps -p "$$owner" >/dev/null 2>&1; then rm -f "$$lock"; continue; fi; \
			echo "==> waiting for simulator lock ($$dest), held by pid $$owner"; sleep 2; \
		done; \
		trap 'rm -f "$$lock"' EXIT INT TERM HUP; \
	fi

.DEFAULT_GOAL := help
.PHONY: help generate test test-app test-store
# `make test` runs two xcodebuild passes because a single invocation targets one OS: the full
# app suite on iOS 26 (where the StoreKit suites auto-skip) and the StoreKit suites on iOS 18.x
# (where SKTestSession actually serves products). .NOTPARALLEL keeps `make -j` from running
# both passes at once and colliding on the shared build.
.NOTPARALLEL:

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

define DEV_YML_STUB
settings:
  base:
    DEVELOPMENT_TEAM: ""
endef
export DEV_YML_STUB

dev.yml:
	@echo "dev.yml not found, creating empty stub (set DEVELOPMENT_TEAM for local signing)"
	@echo "$$DEV_YML_STUB" > dev.yml

generate: dev.yml ## Regenerate MC1.xcodeproj from project.yml (xcodegen)
	xcodegen generate

test: test-app test-store ## Run everything: full app suite (iOS 26) + StoreKit suites (iOS 18)

test-app: generate ## Run the full app suite on iOS 26 (StoreKit suites auto-skip here)
	@dest='$(SIM)'; $(SIM_LOCK); \
		xcodebuild test -project $(PROJECT) -scheme $(SCHEME) \
		-destination "$$dest" 2>&1 | xcsift -f toon

test-store: generate ## Run every StoreKit/IAP SKTestSession suite on iOS 18.x
	@dest='$(STORE_SIM)'; $(SIM_LOCK); \
		xcodebuild test -project $(PROJECT) -scheme $(SCHEME) \
		-destination "$$dest" $(STORE_SUITES) 2>&1 | xcsift -f toon
