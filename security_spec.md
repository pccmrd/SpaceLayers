# HexWell Firestore Security Specification

## 1. Data Invariants
1. **Strict Multi-Tenant Ownership**: Every `SectorState` (`/users/{userId}/sectors/{sectorId}`) and `HexCellSeed` (`/users/{userId}/hex_cells/{cellId}`) belongs exclusively to `userId == request.auth.uid`, and `data.userId == request.auth.uid`.
2. **Relational Consistency (No Orphaned Hexes)**: A `HexCellSeed` cannot be created unless its parent sector `/users/{userId}/sectors/{sectorId}` already exists (`exists(...)`) or is created atomically in the same batch (`existsAfter(...)`).
3. **Immutable Coordinates & Identity**: `userId`, `sectorId`, `q`, `r`, `layerIndex`, `galaxySeed`, and `createdAt` can never be mutated on update.
4. **Compact Procedural Storage Only**: `proceduralFormula` is bounded to `<= 1200` chars and `loreSummary` to `<= 600` chars; all strings and IDs have strict regex and size caps to prevent Denial-of-Wallet attacks.
5. **Query Enforcer**: `allow list` verifies `existing().userId == request.auth.uid`, requiring client queries to filter with `.whereEqualTo("userId", uid)`.

## 2. Adversarial "Dirty Dozen" Payloads
1. **Unauthenticated Sector Read**: Anonymous `get`/`list` on `/users/alice/sectors` -> Rejected (`!isSignedIn()`).
2. **Cross-User Sector Read**: Bob (`bob_456`) reads `/users/alice_123/sectors/sec1` -> Rejected (`request.auth.uid != userId`).
3. **Identity Spoofing on Create**: Alice writes `/users/alice_123/sectors/sec1` with `userId: "bob_456"` -> Rejected (`data.userId != request.auth.uid`).
4. **Unfiltered List Query**: Alice queries `/users/alice_123/hex_cells` when rules enforce `existing().userId == request.auth.uid` without matching ownership or across another user's path -> Rejected.
5. **Orphaned Hex Creation**: Alice creates `/users/alice_123/hex_cells/c1` referencing non-existent `sectorId: "ghost_sector"` -> Rejected (`!exists(...) && !existsAfter(...)`).
6. **Shadow / Ghost Field Injection**: Update includes unauthorized field `isAdmin: true` -> Rejected by `affectedKeys().hasOnly(...)` and `keys().hasOnly(...)`.
7. **Coordinate Mutation on Update**: Alice attempts to change `q`, `r`, or `layerIndex` of an existing `HexCellSeed` -> Rejected by immutable field assertions and `affectedKeys().hasOnly(...)`.
8. **CreatedAt Tampering**: Update attempts to overwrite `createdAt` -> Rejected (`incoming().createdAt != existing().createdAt`).
9. **Future Timestamp Spoofing**: Client submits `createdAt` in the future (`> request.time`) -> Rejected (`data.createdAt <= request.time`).
10. **ID Poisoning**: Document ID contains spaces/slashes or exceeds 128 chars -> Rejected by `isValidId(...)`.
11. **Value / Size Poisoning**: `proceduralFormula` exceeds 1200 chars or `voxelSubRings` = 99 -> Rejected by `isValidHexCellSeed(...)`.
12. **Type Confusion**: `gravityMass` sent as string `"heavy"` on update -> Rejected because `isValidHexCellSeed(incoming())` wraps the entire `allow update` block.
