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
- Live one-way microphone WebRTC uplink code: Parent requests a session; Child must approve in-app and grant Android microphone permission; the Child foreground service shows an ongoing microphone notification/privacy indicator; Parent receives the live audio. This path is not yet validated end-to-end.
- Live camera WebRTC uplink code: Parent requests a session; Child must approve in-app and grant Android CAMERA permission; the Child camera foreground service displays an ongoing notification and Android camera privacy indicator; Parent receives the live video. This path is not yet validated end-to-end.
- Parent views for shared camera photos, screen snapshots, and audio recordings.
- FCM receiver and Cloud Functions source for Parent notifications on geofence events and Child permission requests. These push notifications require the Firebase deployment steps below.

## Not implemented / important limitations

- **Real-time screen mirroring code is now present but not yet validated end-to-end.** Parent can create a WebRTC receive-only offer and view UI; Child receives a request, must explicitly approve it, and must also approve Android MediaProjection before the foreground service starts screen capture. SDP/ICE signalling is stored in Firestore. The current build uses a public STUN server only; there is no TURN relay configuration, so connections across restrictive NATs/mobile networks may fail. Do not treat this as production-ready until both devices are tested on separate networks.
- **Live remote microphone streaming code is present but not yet validated end-to-end.** It uses a separate Firestore-signalled WebRTC audio session and a Child-approved microphone foreground service. The current setup uses STUN only; restrictive NATs may require TURN. Do not treat this as production-ready until tested on two physical devices.
- **Live remote camera implementation is present but not yet validated end-to-end.** It requires in-app Child approval, Android CAMERA permission, a visible camera foreground-service notification/privacy indicator, and network connectivity. The current setup uses STUN only; restrictive NATs may require TURN. Do not treat it as production-ready until tested on two physical devices.
- FCM background notifications do not work until Cloud Functions are deployed and tested.
- Accessibility-based app blocking is not tamper-proof. The Child can disable the service in Android Settings. Strong device-wide enforcement generally requires managed-device / Device Owner provisioning.
- Website filtering is DNS-based and can be bypassed by encrypted DNS or other network paths.
- CI success means the APKs compile. Physical-device behavior, Firebase permissions, OEM background restrictions, and end-to-end pairing still need real-device validation.

Sensitive capture remains visible and permission-based. Android system consent dialogs, foreground-service notifications, and privacy indicators cannot be hidden or bypassed.

## Security rules tests

The GitHub Actions workflow starts the Firestore Emulator and runs `tests/firestore.rules.test.js`. These tests cover 50-minute pairing-code expiry and one-time Child consumption, linked Parent session creation, Child approval/denial, session ending, and participant-only ICE candidate writes for screen, live-audio, and live-camera signalling. They validate Firestore authorization rules; they do not test media transport or prove that live mirroring works. Android CI compilation is also required after the WebRTC changes.

## Authentication and pairing\n\n- Both apps now require Google Sign-In and exchange the Google ID token for a Firebase credential. Anonymous authentication is not used.\n- Parent generates a cryptographically random 8-character pairing code using `SecureRandom`; the code expires after 50 minutes and is consumed once by a Child account.\n- Google Sign-In cannot complete until the Firebase Google provider, Android signing fingerprints, OAuth Web client ID, and refreshed `google-services.json` files are configured as described below.\n\n## Firebase setup

1. Place the Firebase project's `google-services.json` in both Android app module directories (`parent/` and `child/`).
2. In Firebase Authentication, enable the **Google** sign-in provider and configure its project support email. Add the SHA-1 and SHA-256 fingerprints for both Android application IDs (`com.familyguard.parent` and `com.familyguard.child`) in Firebase Project Settings.\n3. Create an OAuth 2.0 **Web application** client ID in Google Cloud Console for the same Firebase project. Put that Web client ID (ending in `.apps.googleusercontent.com`) in both `parent/src/main/res/values/google_auth.xml` and `child/src/main/res/values/google_auth.xml`, replacing `REPLACE_WITH_WEB_CLIENT_ID`. Do not use an Android OAuth client ID here. Download fresh `google-services.json` files after enabling Google sign-in and put them in both module directories; they must include the OAuth client configuration.\n4. Create/enable Cloud Firestore and Firebase Storage.
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

## Default profile avatars
- Parent app uses `parent/src/main/res/drawable/parent_profile_avatar.xml`.
- Child app uses `child/src/main/res/drawable/child_profile_avatar.xml`.
- Both are bundled Android vector drawables and displayed in their respective app headers.
