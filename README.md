# FamilyGuard

FamilyGuard contains two Android apps: **FamilyGuard Parent** and **FamilyGuard Child**. The project is an early, consent-based family-safety implementation. A successful Android CI build confirms compilation; it does not replace testing on real Parent and Child devices.

## Implemented code paths

- 8-character pairing code with a 50-minute expiry and one-time consumption.
- Child-visible location sharing, latest location, location history, and geofence entry/exit events.
- Parent controls for package-based app-block rules and daily app usage limits. Enforcement depends on the Child enabling FamilyGuard's Accessibility service and Usage Access in Android Settings.
- DNS-based website filtering through Android's user-approved VPN service; browser-domain reporting is limited to DNS requests visible to that VPN.
- Child-initiated camera thumbnail sharing. The Child opens the camera app and explicitly takes the photo.
- Child-approved one-time screen snapshot using Android MediaProjection consent.
- Child-started audio recording (up to 60 seconds) which the Child explicitly stops and shares.
- Live one-way microphone WebRTC uplink code: Parent requests a session; Child must approve in-app and grant Android microphone permission; the Child foreground service shows an ongoing microphone notification/privacy indicator; Parent receives the live audio. This new path is not yet validated end-to-end.
- Parent views for shared camera photos, screen snapshots, and audio recordings.
- FCM receiver and Cloud Functions source for Parent notifications on geofence events and Child permission requests. These push notifications require the Firebase deployment steps below.

## Not implemented / important limitations

- **Real-time screen mirroring code is now present but not yet validated end-to-end.** Parent can create a WebRTC receive-only offer and view UI; Child receives a request, must explicitly approve it, and must also approve Android MediaProjection before the foreground service starts screen capture. SDP/ICE signalling is stored in Firestore. The current build uses a public STUN server only; there is no TURN relay configuration, so connections across restrictive NATs/mobile networks may fail. Do not treat this as production-ready until both devices are tested on separate networks.
- **Live remote microphone streaming code is present but not yet validated end-to-end.** It uses a separate Firestore-signalled WebRTC audio session and a Child-approved microphone foreground service. The current setup uses STUN only; restrictive NATs may require TURN. Do not treat this as production-ready until tested on two physical devices.
- **Live remote camera activation is not implemented.** Camera sharing remains Child-initiated; no Parent-triggered camera stream exists.
- FCM background notifications do not work until Cloud Functions are deployed and tested.
- Accessibility-based app blocking is not tamper-proof. The Child can disable the service in Android Settings. Strong device-wide enforcement generally requires managed-device / Device Owner provisioning.
- Website filtering is DNS-based and can be bypassed by encrypted DNS or other network paths.
- CI success means the APKs compile. Physical-device behavior, Firebase permissions, OEM background restrictions, and end-to-end pairing still need real-device validation.

Sensitive capture remains visible and permission-based. Android system consent dialogs, foreground-service notifications, and privacy indicators cannot be hidden or bypassed.

## Security rules tests

The GitHub Actions workflow starts the Firestore Emulator and runs `tests/firestore.rules.test.js`. These tests cover linked Parent session creation, Child approval/denial, session ending, and participant-only ICE candidate writes for screen and live-audio signalling. They validate Firestore authorization rules; they do not test media transport or prove that live mirroring works. Android CI compilation is also required after the WebRTC changes.

## Firebase setup

1. Place the Firebase project's `google-services.json` in both Android app module directories (`parent/` and `child/`).
2. In Firebase Authentication, enable **Anonymous** sign-in.
3. Create/enable Cloud Firestore and Firebase Storage.
4. Publish the repository rules:
   ```sh
   firebase deploy --only firestore:rules,storage
   ```
5. To enable background Parent push notifications, use Firebase CLI with the account that owns project `familyguard-83164`:
   ```sh
   npm install -g firebase-tools
   firebase login
   firebase use familyguard-83164
   cd functions
   npm install
   cd ..
   firebase deploy --only functions
   ```
   Cloud Functions deployment requires the Firebase project's billing plan and supported region configuration. The function sends FCM data messages for geofence events and Child permission requests. Allow notification permission on the Parent phone.

## Build

GitHub Actions builds debug APKs for both Android modules. Download the `familyguard-debug-apks` artifact from a successful workflow run:
https://github.com/shivafamily146-chemistry/FamilyGuard/actions

Requirements: JDK 17 and Android SDK Platform 35.