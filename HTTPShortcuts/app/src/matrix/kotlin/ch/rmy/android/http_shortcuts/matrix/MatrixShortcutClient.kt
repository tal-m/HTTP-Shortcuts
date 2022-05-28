package ch.rmy.android.http_shortcuts.matrix

import android.content.ContentResolver
import android.content.Context
import ch.rmy.android.framework.extensions.logInfo
import ch.rmy.android.http_shortcuts.data.models.ShortcutModel
import ch.rmy.android.http_shortcuts.http.FileUploadManager
import ch.rmy.android.http_shortcuts.http.HttpHeaders
import ch.rmy.android.http_shortcuts.http.ResponseFileStorage
import ch.rmy.android.http_shortcuts.http.ShortcutResponse
import ch.rmy.android.http_shortcuts.variables.VariableManager
import ch.rmy.android.http_shortcuts.variables.Variables
import io.ktor.http.*
import io.reactivex.Single
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.rx2.rxSingle
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.store.StoreFactory
import net.folivo.trixnity.client.store.exposed.ExposedStoreFactory
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.MatrixId
import net.folivo.trixnity.core.model.RoomAliasId
import net.folivo.trixnity.core.model.RoomId
import okhttp3.Headers.Builder
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import javax.inject.Inject
import kotlin.random.Random
import kotlin.system.measureTimeMillis

val dummyHeaders: HttpHeaders = HttpHeaders.parse(Builder().add("Content-Type: text/plain").build())


fun connectToDb(context: Context, dbSuffix: String): Database {
    val outputDir: File = context.cacheDir // context being the Activity pointer

    return Database.connect("jdbc:h2:${outputDir}/trixnity-h2-${dbSuffix}.db;DB_CLOSE_DELAY=-1;")
}

data class MatrixServer(val baseUrl: Url, val username: String) {
    fun encoded(): String {
        val encodedBaseUrl = baseUrl.toString()
            .replace("^https?://".toRegex(), "")
            .replace("""\W""".toRegex(), "_")
        return "${encodedBaseUrl}__${username}"
    }
}

fun createStoreFactory(context: Context, server: MatrixServer): StoreFactory {
    val db = connectToDb(context, server.encoded())

    return ExposedStoreFactory(
        database = db,
        transactionDispatcher = Dispatchers.IO,
        scope = CoroutineScope(Dispatchers.Default),
    )
}

class MatrixShortcutClient
@Inject
constructor(
    private val context: Context,
)  {
    private val contentResolver: ContentResolver
        get() = context.contentResolver

    private val deviceDb = connectToDb(context, "device")

    object DeviceIdTable : Table() {
        val id = integer("id")
    }

    private val deviceId: Int

    init {
        deviceId = transaction(deviceDb) {
            SchemaUtils.create(DeviceIdTable)
            val query = DeviceIdTable.selectAll()

            val res = query.elementAtOrElse(0) {
                val newId = Random.Default.nextInt()
                DeviceIdTable.insert { it[id] = newId }

                val row = ResultRow.createAndFillDefaults(DeviceIdTable.columns)
                row[DeviceIdTable.id] = newId
                row
            }
            commit()
            res[DeviceIdTable.id]
        }
    }

    fun executeShortcut(
        context: Context,
        shortcut: ShortcutModel,
        variableManager: VariableManager,
        responseFileStorage: ResponseFileStorage,
        fileUploadManager: FileUploadManager? = null,
    ): Single<ShortcutResponse> =
        rxSingle {
            val variables = variableManager.getVariableValuesByIds()
            val scope = this

            val baseurl =
                Url(Variables.rawPlaceholdersToResolvedValues(shortcut.url, variables).trim())

            val roomIdStr = Variables.rawPlaceholdersToResolvedValues(
                shortcut.room,
                variables
            )
            val roomId = MatrixId.of(roomIdStr)

            if (!(roomId is RoomId || roomId is RoomAliasId))
                throw Exception("Bad room id ${roomIdStr}!")

            val username = Variables.rawPlaceholdersToResolvedValues(
                shortcut.username,
                variables
            )
            val password = Variables.rawPlaceholdersToResolvedValues(
                shortcut.password,
                variables
            )
            val authToken = Variables.rawPlaceholdersToResolvedValues(
                shortcut.authToken,
                variables
            )
            val body = Variables.rawPlaceholdersToResolvedValues(
                shortcut.bodyContent,
                variables
            )

            val totalTime = measureTimeMillis {
                logInfo("Creating Matrix client")
                val server = MatrixServer(baseurl, username)
                val storeFactory = createStoreFactory(context, server)
                val matrixClient = MatrixClient.fromStore(
                    storeFactory = storeFactory, scope = scope
                ).getOrThrow() ?: MatrixClient.login(
                    baseUrl = baseurl,
                    IdentifierType.User(username),
                    password,
                    initialDeviceDisplayName = "httpshortcuts-client-${deviceId}",
                    storeFactory = storeFactory,
                    scope = scope
                ).getOrThrow()

                matrixClient.startSync() // important to fully start the client!
                matrixClient.syncState.first { it == SyncState.RUNNING }

                val actualRoomId : RoomId
                if (roomId is RoomAliasId) {
                    logInfo("Resolving Room Alias")
                    actualRoomId = matrixClient.api.rooms.getRoomAlias(roomId).getOrThrow().roomId
                    logInfo("Resolved to ${actualRoomId}")
                } else {
                    actualRoomId = roomId as RoomId
                }

                logInfo("Getting Room")
                val room = matrixClient.room.getById(actualRoomId).first { it != null }

                logInfo("Sending Message")

                matrixClient.room.sendMessage(actualRoomId) {
                    text(body)
                }

                matrixClient.room.getOutbox().first { it.isEmpty() }
                logInfo("Sent Message")

                matrixClient.stopSync(true)
            }

            ShortcutResponse(
                url = baseurl.toString(), headers = dummyHeaders, statusCode = 200,
                contentFile = null, timing = totalTime
            )
        }
}

