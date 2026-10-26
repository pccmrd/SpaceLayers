const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

function makeValidSector(uid, now) {
  return {
    userId: uid,
    name: "Kepler-94 Basin",
    galaxySeed: 904211,
    shipQ: 0,
    shipR: 0,
    shipLayer: 4,
    energy: 850,
    turnNumber: 1,
    lastEventLog: "Entered Kepler-94 Deep Space.",
    createdAt: now,
    updatedAt: now,
  };
}

function makeValidHexCell(uid, sectorId, now) {
  return {
    userId: uid,
    sectorId: sectorId,
    q: 1,
    r: -1,
    layerIndex: 3,
    seed: 778899,
    archetype: "ORBITAL_CITADEL",
    voxelSubRings: 4,
    proceduralFormula: "ARCH=ORBITAL_CITADEL;RINGS=4;SPIRES=3;DOME=1;TWIST=2",
    gravityMass: 4.5,
    atmosphereDensity: 0.2,
    elevationOffset: 0.0,
    primaryColorHex: "#00E5FF",
    secondaryColorHex: "#1E293B",
    accentColorHex: "#FFB300",
    title: "Aegis Relay Alpha",
    loreSummary: "Hexagonal orbital ring anchoring local gravity telemetry.",
    lastActionContext: "Player scanned orbital anomaly",
    evolutionCount: 1,
    createdAt: now,
    updatedAt: now,
  };
}

test("1. Unauthenticated user cannot read or list sectors or hex_cells", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.doc(`users/${ALICE_UID}/sectors/sec_1`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/hex_cells`).get());
});

test("2. Authenticated owner can create sector and linked hex_cell, and query with userId filter", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();

  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/sectors/sec_1`).set(makeValidSector(ALICE_UID, now))
  );
  await assertSucceeds(
    aliceDb
      .doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`)
      .set(makeValidHexCell(ALICE_UID, "sec_1", now))
  );

  await assertSucceeds(
    aliceDb
      .collection(`users/${ALICE_UID}/hex_cells`)
      .where("userId", "==", ALICE_UID)
      .where("sectorId", "==", "sec_1")
      .get()
  );
});

test("3. Cross-user isolation: Bob cannot read, list, or mutate Alice's sector or hex_cells", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.doc(`users/${ALICE_UID}/sectors/sec_1`).set(makeValidSector(ALICE_UID, now));
    await adminDb
      .doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`)
      .set(makeValidHexCell(ALICE_UID, "sec_1", now));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.doc(`users/${ALICE_UID}/sectors/sec_1`).get());
  await assertFails(
    bobDb
      .collection(`users/${ALICE_UID}/hex_cells`)
      .where("userId", "==", ALICE_UID)
      .get()
  );
  await assertFails(bobDb.doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`).delete());
});

test("4. Orphaned write rejected: cannot create hex_cell when parent sector does not exist", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date();
  await assertFails(
    aliceDb
      .doc(`users/${ALICE_UID}/hex_cells/ghost_3_1_m1`)
      .set(makeValidHexCell(ALICE_UID, "ghost_sector", now))
  );
});

test("5. Shadow update & immutable field protection: cannot inject ghost fields or mutate coordinates", async () => {
  const now = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = ctx.firestore();
    await adminDb.doc(`users/${ALICE_UID}/sectors/sec_1`).set(makeValidSector(ALICE_UID, now));
    await adminDb
      .doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`)
      .set(makeValidHexCell(ALICE_UID, "sec_1", now));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const later = new Date();

  // Attempt Ghost Field injection
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`).update({
      isVerified: true,
      updatedAt: later,
    })
  );

  // Attempt immutable coordinate mutation
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`).update({
      q: 5,
      updatedAt: later,
    })
  );

  // Valid evolution update succeeds
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/hex_cells/sec_1_3_1_m1`).update({
      seed: 991122,
      proceduralFormula: "ARCH=ORBITAL_CITADEL;RINGS=5;SPIRES=6;DOME=2;TWIST=3",
      voxelSubRings: 5,
      evolutionCount: 2,
      lastActionContext: "Upgraded orbital ring defense array",
      updatedAt: later,
    })
  );
});
