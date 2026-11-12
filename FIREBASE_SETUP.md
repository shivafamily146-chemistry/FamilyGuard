# FamilyGuard Firebase setup

The Android modules are registered to the same Firebase project: `familyguard-83164`.

## Required one-time console setup

1. Open Firebase Console and select the `familyguard-83164` project.
2. Build > Authentication > Get started > Sign-in method > enable **Anonymous**.
3. Build > Firestore Database > Create database. Choose a region close to your users.
4. In Firestore Rules, paste the contents of the repository's `firestore.rules` file and Publish.
5. Rebuild/install both Parent and Child APKs.

## Pairing test

1. Open Parent app and tap **Generate pairing code**.
2. On Child app, enter the 8-character code within 50 minutes and tap **Pair with Parent**.
3. Child can send permission requests; Parent sees pending requests and can approve or deny.
4. Android system permission prompts remain controlled by Android and the person using the Child phone.

## Current implementation boundary

This commit implements anonymous Firebase sign-in, one-time pairing-code exchange, Firestore family links, and Parent approval request UI. It does not yet implement all 13 requested parental-control features. In particular, approving a request records the Parent decision; it does not silently grant Android permissions. Location upload/history, geofencing, usage reports, app/web controls, notification delivery, and consent-based camera/microphone/screen sessions require additional implementation and device testing. Uninstall prevention requires Android managed-device / Device Owner provisioning and cannot be guaranteed by a normal app install.

Do not use production personal data until Firestore rules have been reviewed and tested for your deployment.
