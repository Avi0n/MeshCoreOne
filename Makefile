# Makefile — local developer shortcuts.
# Release and CI automation (certs, TestFlight, App Store) live in fastlane/Fastfile.

SCHEME  := MC1
PROJECT := MC1.xcodeproj

# StoreKit (SKTestSession) suites must run on an iOS 18.x simulator. Under `xcodebuild
# test`, iOS 26.x simulators deliver 0 products to storekitd (Apple regression
# FB22237318 / FB22774836), so every product-dependent test falsely fails. A method-level
# `-only-testing` selector also silently runs 0 tests for Swift Testing suites. The target
# below pins iOS 18.x and uses the suite-level filter. Override STORE_SIM if your machine
# has a different iOS 18.x simulator.
STORE_SIM ?= platform=iOS Simulator,name=iPhone 16e,OS=18.6

.DEFAULT_GOAL := help
.PHONY: help generate test-store

help: ## List available targets
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  %-14s %s\n", $$1, $$2}'

generate: ## Regenerate MC1.xcodeproj from project.yml (xcodegen)
	xcodegen generate

test-store: generate ## Run the StoreKit/IAP SKTestSession suite on iOS 18.x (suite-level filter)
	xcodebuild test -project $(PROJECT) -scheme $(SCHEME) \
		-destination '$(STORE_SIM)' \
		-only-testing:MC1Tests/StoreServiceTests 2>&1 | xcsift -f toon
