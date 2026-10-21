package app.pictureshow

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.preferencesDataStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.JvmServerDiscovery
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

private val Application.preferences by preferencesDataStore("pictureshow")

class PictureshowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@PictureshowApplication)
            modules(
                module {
                    single { preferences }
                    single { AccountStore(AccountPersistence(get())) }
                    single { DeviceIdentityStore(get()) }
                    single<ServerDiscovery> { JvmServerDiscovery() }
                    single {
                        HttpClient(OkHttp) {
                            expectSuccess = false
                            // A probe never follows a redirect to a different base address or sends auth to it.
                            followRedirects = false
                            install(HttpTimeout) {
                                requestTimeoutMillis = 5_000
                                connectTimeoutMillis = 3_000
                                socketTimeoutMillis = 5_000
                            }
                        }
                    }
                    single<JellyfinService> {
                        val name =
                            runCatching {
                                Settings.Global.getString(contentResolver, "device_name")
                            }.getOrNull()?.takeIf { it.isNotBlank() }
                                ?: Build.MODEL
                        PersistentIdentityService(get(), get(), name)
                    }
                    single<CatalogService> { get<JellyfinService>() as CatalogService }
                    viewModel { SessionViewModel(get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server) -> QuickConnectViewModel(server, get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account, session: CatalogSession) -> CatalogHomeViewModel(account, session) }
                    viewModel { (account: Account, item: app.pictureshow.core.MediaItem, session: CatalogSession) ->
                        CatalogGridViewModel(account, item, session)
                    }
                    viewModel { (account: Account, item: app.pictureshow.core.MediaItem, session: CatalogSession) ->
                        CatalogDetailViewModel(account, item, session)
                    }
                }
            )
        }
    }
}

/** Avoid blocking Application.onCreate on a disk read just to construct the protocol client. */
internal class PersistentIdentityService(
    private val http: HttpClient,
    private val identity: DeviceIdentityStore,
    private val deviceName: String
) : JellyfinService,
    CatalogService {
    private suspend fun client(): JellyfinClient = withContext(Dispatchers.IO) {
        JellyfinClient(http, ClientIdentity(deviceName, identity.getOrCreate(), BuildConfig.VERSION_NAME))
    }

    private suspend fun <T> call(block: suspend (JellyfinClient) -> ClientResult<T>): ClientResult<T> = try {
        block(client())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ClientResult.Failure(identityError())
    }

    override suspend fun probe(address: String) = call { it.probe(address) }
    override suspend fun publicUsers(server: Server) = call { it.publicUsers(server) }
    override suspend fun authenticate(server: Server, username: String, password: String) =
        call { it.authenticate(server, username, password) }
    override suspend fun quickConnectEnabled(server: Server) = call { it.quickConnectEnabled(server) }
    override fun quickConnect(server: Server) = kotlinx.coroutines.flow.flow {
        val resolved = try {
            client()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(QuickConnectState.Failed(identityError()))
            return@flow
        }
        resolved.quickConnect(server).collect { emit(it) }
    }
    override suspend fun me(account: Account) = call { it.me(account) }
    override suspend fun logout(account: Account) = call { it.logout(account) }

    override suspend fun views(account: Account) = call { it.views(account) }
    override suspend fun resume(account: Account) = call { it.resume(account) }
    override suspend fun nextUp(account: Account, seriesId: String?) = call { it.nextUp(account, seriesId) }
    override suspend fun latest(account: Account, parentId: String) = call { it.latest(account, parentId) }
    override suspend fun items(account: Account, query: BrowseQuery) = call { it.items(account, query) }
    override suspend fun item(account: Account, id: String) = call { it.item(account, id) }
    override suspend fun seasons(account: Account, seriesId: String) = call { it.seasons(account, seriesId) }
    override suspend fun episodes(account: Account, seriesId: String, seasonId: String) = call { it.episodes(account, seriesId, seasonId) }
    override suspend fun played(account: Account, id: String, value: Boolean) = call { it.played(account, id, value) }
    override suspend fun favorite(account: Account, id: String, value: Boolean) = call { it.favorite(account, id, value) }

    private fun identityError() =
        ClientError(ErrorKind.LOCAL_STORAGE, "Could not read this device's identity. Restart Pictureshow and try again.")
}
