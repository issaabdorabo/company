const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const crypto = require("node:crypto");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_main_123";
const BOB_UID = "bob_sub_456";
const CHARLIE_UID = "charlie_intruder_789";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function sha256(str) {
  return crypto.createHash("sha256").update(str).digest("hex");
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedWorkspaceAndDevice(aliceDb, wsId = "ws_test_1", code = "INV-ABC123", pwd = "P@ss1234", perms = {}) {
  const serverNow = new Date();
  const wsRef = aliceDb.collection("workspaces").doc(wsId);
  await aliceDb.collection("workspaces").doc(wsId).set({
    ownerUid: ALICE_UID,
    workspaceCode: code,
    orgName: "مخازن المؤسسة",
    syncVersion: 1,
    updatedByUid: ALICE_UID,
    updatedByDeviceId: "install_alice_1",
    createdAt: serverNow,
    updatedAt: serverNow,
  });

  const salt = "0123456789abcdef0123456789abcdef";
  const pwdHash = sha256(`${salt}:${pwd}`);
  const inviteHash = sha256(`INVITE:${code}:${pwd}`);
  const devId = "dev_slot_1";

  const canAddItems = perms.canAddItems ?? false;
  const canInward = perms.canInward ?? false;
  const canDisburse = perms.canDisburse ?? false;
  const canEditDelete = perms.canEditDelete ?? false;

  await wsRef.collection("devices").doc(devId).set({
    workspaceId: wsId,
    ownerUid: ALICE_UID,
    deviceName: "جهاز فرعي 1",
    passwordSalt: salt,
    passwordHash: pwdHash,
    inviteHash,
    boundUid: "",
    boundInstallationId: "",
    isClaimed: false,
    isKicked: false,
    canAddItems,
    canInward,
    canDisburse,
    canEditDelete,
    createdAt: serverNow,
    updatedAt: serverNow,
  });

  await aliceDb.collection("pairing_invites").doc(inviteHash).set({
    workspaceId: wsId,
    workspaceCode: code,
    ownerUid: ALICE_UID,
    deviceSlotId: devId,
    deviceName: "جهاز فرعي 1",
    canAddItems,
    canInward,
    canDisburse,
    canEditDelete,
    isUsed: false,
    isRevoked: false,
    boundUid: "",
    boundInstallationId: "",
    createdAt: serverNow,
    updatedAt: serverNow,
  });

  return { wsRef, devId, inviteHash, serverNow };
}

test("Unauthenticated caller is denied on workspaces, devices, entities, and pairing_invites", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("workspaces").doc("ws_1").get());
  await assertFails(unauthDb.collection("workspaces").get());
  await assertFails(unauthDb.collection("pairing_invites").doc("a".repeat(64)).get());
  await assertFails(unauthDb.collection("workspaces").doc("ws_1").collection("entities").get());
});

test("Pairing invites cannot be listed (prevents brute-force discovery)", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("pairing_invites").get());
});

test("Workspace list requires ownerUid filter and isolates between users", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const now = new Date();
    await ctx.firestore().collection("workspaces").doc("ws_alice").set({
      ownerUid: ALICE_UID,
      workspaceCode: "INV-111111",
      orgName: "مخزن أ",
      syncVersion: 1,
      updatedByUid: ALICE_UID,
      updatedByDeviceId: "dev_a",
      createdAt: now,
      updatedAt: now,
    });
  });

  // Unfiltered query fails
  await assertFails(aliceDb.collection("workspaces").get());
  // Owner query succeeds
  await assertSucceeds(aliceDb.collection("workspaces").where("ownerUid", "==", ALICE_UID).get());
  // Bob cannot query Alice's workspaces or direct get
  await assertFails(bobDb.collection("workspaces").where("ownerUid", "==", ALICE_UID).get());
  await assertFails(bobDb.collection("workspaces").doc("ws_alice").get());
});

test("Sub-device pairing succeeds with valid invite hash, blocks privilege escalation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  let inviteHash, devId;
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const res = await seedWorkspaceAndDevice(ctx.firestore(), "ws_shared", "INV-SHARE1", "Secret99", {
      canAddItems: false,
      canInward: false,
      canDisburse: true,
      canEditDelete: false,
    });
    inviteHash = res.inviteHash;
    devId = res.devId;
  });

  // Escalation attempt: Bob tries setting canEditDelete=true when invite had false -> MUST FAIL
  const badBatch = bobDb.batch();
  const now = new Date();
  badBatch.update(bobDb.collection("pairing_invites").doc(inviteHash), {
    isUsed: true,
    boundUid: BOB_UID,
    boundInstallationId: "install_bob_1234",
    updatedAt: now,
  });
  badBatch.set(bobDb.collection("workspaces").doc("ws_shared").collection("members").doc(BOB_UID), {
    uid: BOB_UID,
    workspaceId: "ws_shared",
    ownerUid: ALICE_UID,
    deviceSlotId: devId,
    deviceName: "جهاز فرعي 1",
    inviteHash,
    boundInstallationId: "install_bob_1234",
    isActive: true,
    isKicked: false,
    canAddItems: false,
    canInward: false,
    canDisburse: true,
    canEditDelete: true, // ESCALATION ATTEMPT
    joinedAt: now,
    updatedAt: now,
  });
  await assertFails(badBatch.commit());

  // Legitimate claim with matching permissions -> SUCCEEDS
  const goodBatch = bobDb.batch();
  goodBatch.update(bobDb.collection("pairing_invites").doc(inviteHash), {
    isUsed: true,
    boundUid: BOB_UID,
    boundInstallationId: "install_bob_1234",
    updatedAt: now,
  });
  goodBatch.set(bobDb.collection("workspaces").doc("ws_shared").collection("members").doc(BOB_UID), {
    uid: BOB_UID,
    workspaceId: "ws_shared",
    ownerUid: ALICE_UID,
    deviceSlotId: devId,
    deviceName: "جهاز فرعي 1",
    inviteHash,
    boundInstallationId: "install_bob_1234",
    isActive: true,
    isKicked: false,
    canAddItems: false,
    canInward: false,
    canDisburse: true,
    canEditDelete: false,
    joinedAt: now,
    updatedAt: now,
  });
  goodBatch.update(bobDb.collection("workspaces").doc("ws_shared").collection("devices").doc(devId), {
    isClaimed: true,
    boundUid: BOB_UID,
    boundInstallationId: "install_bob_1234",
    updatedAt: now,
  });
  await assertSucceeds(goodBatch.commit());

  // Charlie cannot hijack Bob's already claimed invite
  await assertFails(
    testEnv.authenticatedContext(CHARLIE_UID).firestore().collection("pairing_invites").doc(inviteHash).update({
      isUsed: true,
      boundUid: CHARLIE_UID,
      boundInstallationId: "install_charlie_999",
      updatedAt: now,
    })
  );
});

test("Granular RBAC & Instant Kick enforcement on workspace entities", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  let inviteHash, devId;
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    const res = await seedWorkspaceAndDevice(db, "ws_rbac", "INV-RBAC1", "Pwd88888", {
      canAddItems: false,
      canInward: false,
      canDisburse: true,
      canEditDelete: false,
    });
    inviteHash = res.inviteHash;
    devId = res.devId;
    const now = new Date();
    await db.collection("workspaces").doc("ws_rbac").collection("members").doc(BOB_UID).set({
      uid: BOB_UID,
      workspaceId: "ws_rbac",
      ownerUid: ALICE_UID,
      deviceSlotId: devId,
      deviceName: "جهاز فرعي",
      inviteHash,
      boundInstallationId: "install_bob_1234",
      isActive: true,
      isKicked: false,
      canAddItems: false,
      canInward: false,
      canDisburse: true,
      canEditDelete: false,
      joinedAt: now,
      updatedAt: now,
    });
  });

  const now = new Date();
  const entitiesCol = bobDb.collection("workspaces").doc("ws_rbac").collection("entities");

  // Bob can read shared entities
  await assertSucceeds(entitiesCol.get());

  // Bob CANNOT add an item (canAddItems = false)
  await assertFails(entitiesCol.doc("item_1").set({
    workspaceId: "ws_rbac",
    entityType: "item",
    localId: 1,
    payload: { name: "صنف غير مصرح" },
    updatedByUid: BOB_UID,
    isDeleted: false,
    updatedAt: now,
  }));

  // Bob CANNOT create INWARD voucher (canInward = false)
  await assertFails(entitiesCol.doc("voucher_1").set({
    workspaceId: "ws_rbac",
    entityType: "voucher",
    localId: 1,
    payload: { voucherType: "INWARD", voucherNumber: "IN-001" },
    updatedByUid: BOB_UID,
    isDeleted: false,
    updatedAt: now,
  }));

  // Bob CAN create DISBURSEMENT voucher (canDisburse = true)
  await assertSucceeds(entitiesCol.doc("voucher_2").set({
    workspaceId: "ws_rbac",
    entityType: "voucher",
    localId: 2,
    payload: { voucherType: "DISBURSEMENT", voucherNumber: "OUT-001" },
    updatedByUid: BOB_UID,
    isDeleted: false,
    updatedAt: now,
  }));

  // Alice kicks Bob -> Bob loses entities access immediately, while still able to read his kicked member status
  await assertSucceeds(
    aliceDb.collection("workspaces").doc("ws_rbac").collection("members").doc(BOB_UID).update({
      isActive: false,
      isKicked: true,
      canAddItems: false,
      canInward: false,
      canDisburse: false,
      canEditDelete: false,
      updatedAt: now,
    })
  );

  // Kicked Bob CANNOT read or write entities anymore
  await assertFails(entitiesCol.get());
  await assertFails(entitiesCol.doc("voucher_3").set({
    workspaceId: "ws_rbac",
    entityType: "voucher",
    localId: 3,
    payload: { voucherType: "DISBURSEMENT", voucherNumber: "OUT-002" },
    updatedByUid: BOB_UID,
    isDeleted: false,
    updatedAt: now,
  }));

  // Kicked Bob can still read his own member status doc to see kick notice
  await assertSucceeds(bobDb.collection("workspaces").doc("ws_rbac").collection("members").doc(BOB_UID).get());
});
