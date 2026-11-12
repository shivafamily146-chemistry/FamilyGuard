const fs = require('node:fs');
const path = require('node:path');
const { before, after, beforeEach, test } = require('node:test');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { Timestamp, doc, setDoc, getDoc, updateDoc } = require('firebase/firestore');

const projectId = 'demo-familyguard';
let env;
const parentUid = 'parent123';
const childUid = 'child123';
const strangerUid = 'stranger123';
const createdAt = Timestamp.fromMillis(1700000000000);

function sessionData() {
  return {
    parentUid,
    childUid,
    status: 'requested',
    createdAt,
    offer: { type: 'offer', sdp: 'v=0\\r\\n' }
  };
}

function candidateData(uid) {
  return {
    senderUid: uid,
    candidate: JSON.stringify({
      candidate: 'candidate:1 1 UDP 2122260223 192.0.2.1 5000 typ host',
      sdpMid: '0',
      sdpMLineIndex: 0
    }),
    createdAt
  };
}

before(async () => {
  const rules = fs.readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8');
  env = await initializeTestEnvironment({ projectId, firestore: { rules } });
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'familyLinks/' + childUid), {
      parentUid, childUid, childLabel: 'Test Child'
    });
  });
});

after(async () => {
  if (env) await env.cleanup();
});

test('only the linked Parent can create a screen session request', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  await assertSucceeds(setDoc(doc(parent, 'screenSessions/session1'), sessionData()));
  await assertFails(setDoc(doc(stranger, 'screenSessions/session2'), sessionData()));
});

test('Child can read its request; unrelated account cannot', async () => {
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'screenSessions/session1'), sessionData());
  });
  const child = env.authenticatedContext(childUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  await assertSucceeds(getDoc(doc(child, 'screenSessions/session1')));
  await assertFails(getDoc(doc(stranger, 'screenSessions/session1')));
});

test('Child may deny a request without an SDP answer', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const child = env.authenticatedContext(childUid).firestore();
  await assertSucceeds(setDoc(doc(parent, 'screenSessions/session1'), sessionData()));
  await assertSucceeds(updateDoc(doc(child, 'screenSessions/session1'), {
    status: 'denied',
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
});

test('only Child may approve with an SDP answer; Parent may end the session', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const child = env.authenticatedContext(childUid).firestore();
  const ref = doc(parent, 'screenSessions/session1');
  await assertSucceeds(setDoc(ref, sessionData()));
  await assertFails(updateDoc(ref, {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\r\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(updateDoc(doc(child, 'screenSessions/session1'), {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\r\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(updateDoc(ref, {
    status: 'ended',
    updatedAt: Timestamp.fromMillis(1700000002000)
  }));
});

test('ICE candidates are limited to the linked session participants and session state', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const child = env.authenticatedContext(childUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  await assertSucceeds(setDoc(doc(parent, 'screenSessions/session1'), sessionData()));
  await assertSucceeds(setDoc(doc(parent, 'screenSessions/session1/candidates/p1'), candidateData(parentUid)));
  await assertFails(setDoc(doc(parent, 'screenSessions/session1/candidates/bad-child'), candidateData(childUid)));
  await assertFails(setDoc(doc(stranger, 'screenSessions/session1/candidates/x'), candidateData(strangerUid)));
  await assertSucceeds(updateDoc(doc(child, 'screenSessions/session1'), {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\r\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(setDoc(doc(child, 'screenSessions/session1/candidates/c1'), candidateData(childUid)));
});

test('only the linked Parent can create a live audio request', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  const data = { ...sessionData(), parentUid, childUid };
  await assertSucceeds(setDoc(doc(parent, 'audioSessions/audio1'), data));
  await assertFails(setDoc(doc(stranger, 'audioSessions/audio2'), { ...data, parentUid: strangerUid }));
});

test('Child must approve live audio and only session participants may signal ICE', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const child = env.authenticatedContext(childUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  const ref = doc(parent, 'audioSessions/audio1');
  await assertSucceeds(setDoc(ref, sessionData()));
  await assertSucceeds(getDoc(doc(child, 'audioSessions/audio1')));
  await assertFails(updateDoc(ref, {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\\\r\\\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(updateDoc(doc(child, 'audioSessions/audio1'), {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\\\r\\\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(setDoc(doc(child, 'audioSessions/audio1/candidates/c1'), candidateData(childUid)));
  await assertFails(setDoc(doc(stranger, 'audioSessions/audio1/candidates/x'), candidateData(strangerUid)));
});

test('only the linked Parent can create a live camera request', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  await assertSucceeds(setDoc(doc(parent, 'cameraSessions/camera1'), sessionData()));
  await assertFails(setDoc(doc(stranger, 'cameraSessions/camera2'), { ...sessionData(), parentUid: strangerUid }));
});

test('Child must approve live camera and only participants may signal ICE', async () => {
  const parent = env.authenticatedContext(parentUid).firestore();
  const child = env.authenticatedContext(childUid).firestore();
  const stranger = env.authenticatedContext(strangerUid).firestore();
  await assertSucceeds(setDoc(doc(parent, 'cameraSessions/camera1'), sessionData()));
  await assertSucceeds(getDoc(doc(child, 'cameraSessions/camera1')));
  await assertFails(updateDoc(doc(parent, 'cameraSessions/camera1'), {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\r\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(updateDoc(doc(child, 'cameraSessions/camera1'), {
    status: 'approved',
    answer: { type: 'answer', sdp: 'v=0\\r\\n' },
    updatedAt: Timestamp.fromMillis(1700000001000)
  }));
  await assertSucceeds(setDoc(doc(child, 'cameraSessions/camera1/candidates/c1'), candidateData(childUid)));
  await assertFails(setDoc(doc(stranger, 'cameraSessions/camera1/candidates/x'), candidateData(strangerUid)));
});
