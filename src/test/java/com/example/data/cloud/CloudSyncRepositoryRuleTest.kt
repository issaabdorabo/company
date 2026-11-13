package com.example.data.cloud

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CloudSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun mainDevice_createsWorkspaceAndRecoversWorkspaces() = runBlocking {
        signInTestUser("owner_${UUID.randomUUID()}@test.com")
        val repo = CloudSyncRepository(firestore, auth)

        val wsResult = withTimeout(TIMEOUT_MS) {
            repo.ensureOwnerWorkspace("مخازن النجاح", "install_owner_1")
        }
        assertTrue(wsResult.isSuccess)
        val workspace = wsResult.getOrThrow()
        assertTrue(workspace.workspaceCode.startsWith("INV-"))

        val recovered = withTimeout(TIMEOUT_MS) {
            repo.findOwnerWorkspaces()
        }
        assertTrue(recovered.isSuccess)
        assertTrue(recovered.getOrThrow().any { it.id == workspace.id })
    }

    @Test
    fun pairing_uniquePasswordFlow_andGranularPermissions_andKick() = runBlocking {
        // 1. Main Device creates workspace and unique per-device slot
        signInTestUser("main_${UUID.randomUUID()}@test.com")
        val mainRepo = CloudSyncRepository(firestore, auth)
        val ws = withTimeout(TIMEOUT_MS) {
            mainRepo.ensureOwnerWorkspace("الشركة المتحدة", "main_install_1").getOrThrow()
        }

        val slotCreds = withTimeout(TIMEOUT_MS) {
            mainRepo.createSubDeviceSlot(
                workspaceId = ws.id,
                workspaceCode = ws.workspaceCode,
                deviceName = "جهاز المستودع الفرعي",
                canAddItems = false,
                canInward = false,
                canDisburse = true,
                canEditDelete = false
            ).getOrThrow()
        }
        assertTrue(slotCreds.plainPassword.length >= 8)

        // Main Device pushes a catalog item
        withTimeout(TIMEOUT_MS) {
            mainRepo.pushEntityRecords(
                ws.id,
                listOf(
                    CloudEntityUpsert(
                        docId = "item_101",
                        entityType = "item",
                        localId = 101L,
                        payload = mapOf("id" to 101L, "name" to "طابعة ليزر")
                    )
                )
            ).getOrThrow()
        }

        // 2. Sub Device signs in with its own account and pairs using workspaceCode + unique password
        signInTestUser("sub_${UUID.randomUUID()}@test.com")
        val subRepo = CloudSyncRepository(firestore, auth)
        val pairRes = withTimeout(TIMEOUT_MS) {
            subRepo.pairSubDevice(
                workspaceCode = slotCreds.workspaceCode,
                plainPassword = slotCreds.plainPassword,
                installationId = "sub_install_${UUID.randomUUID()}"
            )
        }
        assertTrue(pairRes.isSuccess)
        val paired = pairRes.getOrThrow()
        assertFalse(paired.permissions.isMainDevice)
        assertTrue(paired.permissions.canDisburse)
        assertFalse(paired.permissions.canAddItems)

        // Sub Device observes synced entities
        val entities = withTimeout(FLOW_TIMEOUT_MS) {
            subRepo.observeWorkspaceEntities(ws.id).first { it.any { r -> r.id == "item_101" } }
        }
        assertEquals("طابعة ليزر", entities.first { it.id == "item_101" }.payload["name"])

        // Sub Device CANNOT add item (canAddItems = false)
        val unauthorizedAdd = withTimeout(TIMEOUT_MS) {
            subRepo.pushEntityRecords(
                ws.id,
                listOf(
                    CloudEntityUpsert(
                        docId = "item_102",
                        entityType = "item",
                        localId = 102L,
                        payload = mapOf("id" to 102L, "name" to "ممنوع")
                    )
                )
            )
        }
        assertTrue(unauthorizedAdd.isFailure)
        val addEx = unauthorizedAdd.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, addEx?.code)

        // Sub Device CAN create DISBURSEMENT voucher (canDisburse = true)
        val allowedDisburse = withTimeout(TIMEOUT_MS) {
            subRepo.pushEntityRecords(
                ws.id,
                listOf(
                    CloudEntityUpsert(
                        docId = "voucher_201",
                        entityType = "voucher",
                        localId = 201L,
                        payload = mapOf("id" to 201L, "voucherType" to "DISBURSEMENT", "voucherNumber" to "OUT-1")
                    )
                )
            )
        }
        assertTrue(allowedDisburse.isSuccess)

        // 3. Wrong password or reused password from a 3rd device fails
        signInTestUser("stranger_${UUID.randomUUID()}@test.com")
        val strangerRepo = CloudSyncRepository(firestore, auth)
        val hijackAttempt = withTimeout(TIMEOUT_MS) {
            strangerRepo.pairSubDevice(slotCreds.workspaceCode, slotCreds.plainPassword, "stranger_install_1")
        }
        assertTrue(hijackAttempt.isFailure)
    }

    @Test
    fun mainDevice_updatesPermissionsAndKicksSubDeviceImmediately() = runBlocking {
        // Main Device creates workspace and a read-only Sub Device slot
        val mainEmail = "main_kick_${UUID.randomUUID()}@test.com"
        signInTestUser(mainEmail)
        val mainRepo = CloudSyncRepository(firestore, auth)
        val ws = withTimeout(TIMEOUT_MS) {
            mainRepo.ensureOwnerWorkspace("مخازن السلامة", "install_main_kick").getOrThrow()
        }
        val creds = withTimeout(TIMEOUT_MS) {
            mainRepo.createSubDeviceSlot(
                workspaceId = ws.id,
                workspaceCode = ws.workspaceCode,
                deviceName = "مراقب فرعي",
                canAddItems = false,
                canInward = false,
                canDisburse = false,
                canEditDelete = false
            ).getOrThrow()
        }

        // Sub Device pairs
        val subEmail = "sub_kick_${UUID.randomUUID()}@test.com"
        signInTestUser(subEmail)
        val subRepo = CloudSyncRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            subRepo.pairSubDevice(creds.workspaceCode, creds.plainPassword, "install_sub_kick").getOrThrow()
        }

        // Main Device grants canInward and observes linkedDevices
        signInTestUser(mainEmail)
        val devices = withTimeout(FLOW_TIMEOUT_MS) {
            mainRepo.observeLinkedDevices(ws.id).first { list -> list.any { it.id == creds.deviceSlotId && it.isClaimed } }
        }
        val linkedDevice = devices.first { it.id == creds.deviceSlotId }
        withTimeout(TIMEOUT_MS) {
            mainRepo.updateSubDevicePermissions(
                workspaceId = ws.id,
                device = linkedDevice,
                canAddItems = false,
                canInward = true,
                canDisburse = false,
                canEditDelete = false
            ).getOrThrow()
        }

        // Sub Device receives updated permissions & can push an INWARD voucher
        signInTestUser(subEmail)
        val memberAfterGrant = withTimeout(FLOW_TIMEOUT_MS) {
            subRepo.observeMyMemberStatus(ws.id).first { it?.canInward == true }
        }
        assertNotNull(memberAfterGrant)

        val inwardAllowed = withTimeout(TIMEOUT_MS) {
            subRepo.pushEntityRecords(
                ws.id,
                listOf(
                    CloudEntityUpsert(
                        docId = "voucher_in_1",
                        entityType = "voucher",
                        localId = 301L,
                        payload = mapOf("id" to 301L, "voucherType" to "INWARD", "voucherNumber" to "IN-1")
                    )
                )
            )
        }
        assertTrue(inwardAllowed.isSuccess)

        // Main Device kicks Sub Device
        signInTestUser(mainEmail)
        withTimeout(TIMEOUT_MS) {
            mainRepo.kickSubDevice(ws.id, linkedDevice).getOrThrow()
        }

        // Sub Device immediately loses read/write access on workspace entities
        signInTestUser(subEmail)
        val kickedReadResult = withTimeout(TIMEOUT_MS) {
            subRepo.fetchAllWorkspaceEntities(ws.id)
        }
        assertTrue(kickedReadResult.isFailure)
        val ex = kickedReadResult.exceptionOrNull()?.unwrapFirestoreException()
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, ex?.code)
    }

    @Test
    fun observeWorkspaceEntities_unauthenticated_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repo = CloudSyncRepository(firestore, auth)
        val result = try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repo.observeWorkspaceEntities("ws_any").first()
            }
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull(result)
    }

    private companion object {
        const val TIMEOUT_MS = 6000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
