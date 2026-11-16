package com.example.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cloud.LinkedSubDevice
import com.example.data.database.AppDatabase
import com.example.data.model.Item
import com.example.data.model.Warehouse
import com.example.data.repository.InventoryRepository
import com.example.util.SecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class LocalNetworkSyncEndToEndTest {

    private lateinit var mainDb: AppDatabase
    private lateinit var subDb: AppDatabase
    private lateinit var mainRepo: InventoryRepository
    private lateinit var subRepo: InventoryRepository
    private lateinit var serverScope: CoroutineScope
    private lateinit var server: LocalNetworkSyncServer
    private lateinit var client: LocalNetworkSyncClient

    private val workspaceCode = "INV-TEST99"
    private val syncVersion = AtomicLong(1L)
    private val linkedSlots = AtomicReference<List<LinkedSubDevice>>(emptyList())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        mainDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        subDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mainRepo = InventoryRepository(mainDb, context)
        subRepo = InventoryRepository(subDb, context)

        serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        client = LocalNetworkSyncClient()
        server = LocalNetworkSyncServer(
            scope = serverScope,
            getWorkspaceCode = { workspaceCode },
            getOrgName = { "مخازن الاختبار" },
            getSyncVersion = { syncVersion.get() },
            getLinkedDevices = { linkedSlots.get() },
            onSaveLinkedDevices = { linkedSlots.set(it) },
            onExportEntities = { mainRepo.exportAllEntitiesForCloud() },
            onApplyRemoteEntities = { records ->
                mainRepo.replaceDatabaseFromCloudEntitiesSafely(records)
                syncVersion.incrementAndGet()
            }
        )
    }

    @After
    fun tearDown() {
        server.stop()
        serverScope.cancel()
        mainDb.close()
        subDb.close()
    }

    @Test
    fun pairing_liveSync_oneDeviceBinding_permissionEnforcement_and_kickWorkEndToEnd() = runBlocking {
        // 1. Seed Main Device data & create a Sub-Device slot with salted SHA-256 unique password
        val wId = mainRepo.insertWarehouse(
            Warehouse(name = "مستودع الشمال", location = "صنعاء", managerName = "عيسى")
        )
        mainRepo.insertItem(
            Item(name = "طابعة ليزر HP", barcode = "LP-900", unit = "حبة", minStockLevel = 2.0)
        )
        assertTrue(wId > 0L)

        val plainPwd = "Secret#987"
        val salt = SecurityUtils.generateSalt()
        val hash = SecurityUtils.hashPassword(plainPwd, salt)
        val slot = LinkedSubDevice(
            id = "slot-1",
            deviceName = "هاتف الفرع الأول",
            passwordSalt = salt,
            passwordHash = hash,
            sessionToken = "tok-sub-1",
            canAddItems = false,
            canInward = false,
            canDisburse = true,
            canEditDelete = false
        )
        linkedSlots.set(listOf(slot))

        val port = server.start(preferredPort = 0, bindLoopbackOnly = true)
        assertTrue(port > 0)
        val host = "127.0.0.1:$port"

        // 2. Wrong password must fail with INVALID_PASSWORD
        val badResult = client.pairWithMainDevice(host, workspaceCode, "WrongPass", "device-A")
        assertTrue(badResult.isFailure)
        assertEquals("INVALID_PASSWORD", (badResult.exceptionOrNull() as SyncApiException).errorCode)

        // 3. Valid pairing syncs full inventory to Sub-Device
        val pairResult = client.pairWithMainDevice(host, workspaceCode, plainPwd, "device-A")
        assertTrue(pairResult.isSuccess)
        val paired = pairResult.getOrThrow()
        assertEquals("tok-sub-1", paired.sessionToken)
        assertFalse(paired.permissions.canAddItems)
        assertTrue(paired.permissions.canDisburse)

        subRepo.replaceDatabaseFromCloudEntitiesSafely(paired.entities)
        assertEquals(1, subRepo.allWarehouses.first().size)
        assertEquals("طابعة ليزر HP", subRepo.allItems.first().first().name)

        // 4. Different device using same password must be blocked (1-device security lock)
        val hijackResult = client.pairWithMainDevice(host, workspaceCode, plainPwd, "device-B")
        assertTrue(hijackResult.isFailure)
        assertEquals("DEVICE_ALREADY_BOUND", (hijackResult.exceptionOrNull() as SyncApiException).errorCode)

        // 5. Sub-Device pushing an unauthorized actionType must be rejected with PERMISSION_DENIED
        val pushDenied = client.pushEntitiesToMain(
            host,
            paired.sessionToken,
            "ADD_OR_MANAGE_CATALOG",
            subRepo.exportAllEntitiesForCloud()
        )
        assertTrue(pushDenied.isFailure)
        assertEquals("PERMISSION_DENIED", (pushDenied.exceptionOrNull() as SyncApiException).errorCode)

        // 6. Main grants ADD_OR_MANAGE_CATALOG & increments revision -> Sub sees updated permission + new item
        linkedSlots.set(linkedSlots.get().map { it.copy(canAddItems = true) })
        mainRepo.insertItem(Item(name = "شاشة ديل 24", barcode = "DM-24", unit = "حبة"))
        val newRev = syncVersion.incrementAndGet()

        val snap = client.fetchSyncSnapshot(host, paired.sessionToken).getOrThrow()
        assertEquals(newRev, snap.syncVersion)
        assertTrue(snap.permissions.canAddItems)
        subRepo.replaceDatabaseFromCloudEntitiesSafely(snap.entities)
        assertEquals(2, subRepo.allItems.first().size)

        // 7. Kick Sub-Device -> next pull must fail with KICKED
        linkedSlots.set(linkedSlots.get().map { it.copy(isKicked = true) })
        val kickedSnap = client.fetchSyncSnapshot(host, paired.sessionToken)
        assertTrue(kickedSnap.isFailure)
        assertEquals("KICKED", (kickedSnap.exceptionOrNull() as SyncApiException).errorCode)
    }

    @Test
    fun externalFileBackup_restoresCleanlyAfterReinstallOnFreshSeededDatabase() = runBlocking {
        // Original installation data
        mainRepo.insertWarehouse(Warehouse(name = "المخزن المعتمد", managerName = "أحمد"))
        mainRepo.insertItem(Item(name = "مولد كهربائي 5 ك.و", barcode = "GEN-05", unit = "وحدة"))
        val exportedJson = mainRepo.createBackup("تصدير خارجي").backupJson

        // Fresh reinstall on new device (seeded with default data first)
        subRepo.seedInitialDataIfEmpty()
        assertTrue(subRepo.allWarehouses.first().isNotEmpty())

        // Restore external JSON replacing seeded defaults cleanly
        val restored = subRepo.restoreBackupReplacingExisting(exportedJson)
        assertTrue(restored)

        val restoredWarehouses = subRepo.allWarehouses.first()
        val restoredItems = subRepo.allItems.first()
        assertEquals(1, restoredWarehouses.size)
        assertEquals("المخزن المعتمد", restoredWarehouses.first().name)
        assertEquals(1, restoredItems.size)
        assertEquals("مولد كهربائي 5 ك.و", restoredItems.first().name)
    }
}
