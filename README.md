# FamilyGuard

FamilyGuard is an Android family-safety project with two separate applications: FamilyGuard Parent and FamilyGuard Child.

## Current status

This is an early Android starter build, not a complete parental-control product. The current screens are scaffolding only. Device pairing, authentication, live GPS transport, geofencing, location history, screen-time enforcement, app and website blocking, reports, push notifications, and secure backend are not implemented yet. Camera, microphone, and screen-sharing features must remain visible, permission-based, and consent-driven.

## Build

GitHub Actions builds debug APKs on pushes to the main branch and on manual workflow dispatch. Download the familyguard-debug-apks artifact from a successful workflow run.

## Requirements

JDK 17 and Android SDK Platform 35.

## Privacy and platform notes

Android requires runtime permissions and, for background location or other sensitive capabilities, additional platform-compliant disclosures and foreground-service handling. iOS does not expose equivalent unrestricted device-control APIs; iOS support requires a separate design using Apple's supported frameworks.
