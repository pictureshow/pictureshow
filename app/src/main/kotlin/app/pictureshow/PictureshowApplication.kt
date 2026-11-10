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
import app.pictureshow.core.SearchKind
import app.pictureshow.core.SearchService
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val Application.preferences by preferencesDataStore("pictureshow")

/** Use the same physical TV name for catalog, reports and streaming requests. */
internal fun deviceDisplayName(context: android.content.Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, "device_name") }
        .getOrNull()?.takeIf(String::isNotBlank) ?: Build.MODEL

class PictureshowApplication : Application() {
    internal val supporter by lazy {
        SupporterController(
            PlaySupporterBilling(this),
            SupporterStore(preferences),
            CoroutineScope(
                SupervisorJob() + Dispatchers.Main.immediate
            )
        )
    }
    private var watchNext: WatchNextCoordinator? = null

    internal fun refreshWatchNext() {
        watchNext?.refresh()
    }
    override fun onCreate() {
        super.onCreate()
        val dependencyGraph = startKoin {
            androidContext(this@PictureshowApplication)
            modules(
                module {
                    single { preferences }
                    single { AccountStore(AccountPersistence(get())) }
                    single { DeviceIdentityStore(get()) }
                    single { PlaybackPreferencesStore(get()) }
                    single<ServerDiscovery> { JvmServerDiscovery() }
                    // Lazy on purpose: only the touch interface ever resolves it, and it reaches the Cast SDK later still.
                    single { castSenderHost(this@PictureshowApplication, get(), get()) }
                    single {
                        val name = deviceDisplayName(this@PictureshowApplication)
                        val store = get<DeviceIdentityStore>()
                        val http = get<HttpClient>()
                        suspend fun remote() = app.pictureshow.core.control.RemoteClient(
                            http,
                            app.pictureshow.core.ClientIdentity(name, store.getOrCreate(), BuildConfig.VERSION_NAME)
                        )
                        RemoteControllerHost(
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate),
                            object : RemoteControllerGateway {
                                override suspend fun targets(account: Account) = remote().targets(account)
                                override suspend fun send(account: Account, request: app.pictureshow.core.control.RemoteRequest) =
                                    remote().send(account, request)
                            },
                            android.os.SystemClock::elapsedRealtime
                        )
                    }
                    single {
                        val name = deviceDisplayName(this@PictureshowApplication)
                        val store = get<DeviceIdentityStore>()
                        val http = get<HttpClient>()
                        suspend fun identity(account: Account) =
                            app.pictureshow.core.ClientIdentity(name, store.getOrCreate(), BuildConfig.VERSION_NAME).forAccount(account)
                        RemoteTargetHost(
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate),
                            OkHttpRemoteSocketFactory(),
                            register = { account, commands ->
                                app.pictureshow.core.control.RemoteClient(http, identity(account))
                                    .registerTarget(account, commands.isNotEmpty(), commands)
                            },
                            authorization = { account ->
                                app.pictureshow.core.AuthorizationHeader.build(identity(account), account.accessToken)
                            },
                            nowMs = android.os.SystemClock::elapsedRealtime,
                            trace = PlaybackDiagnostics::write
                        )
                    }
                    single {
                        createApiHttpClient()
                    }
                    single<JellyfinService> {
                        val name = deviceDisplayName(this@PictureshowApplication)
                        PersistentIdentityService(get(), get(), name)
                    }
                    single<app.pictureshow.core.livetv.LiveTvService> {
                        app.pictureshow.core.livetv.LiveTvClient(
                            get(),
                            ClientIdentity(deviceDisplayName(this@PictureshowApplication), "live-tv", BuildConfig.VERSION_NAME),
                            LivePendingStore(this@PictureshowApplication)
                        )
                    }
                    viewModel { (account: Account) -> LiveTvViewModel(account, get()) }
                    single<CatalogService> { get<JellyfinService>() as CatalogService }
                    single<app.pictureshow.core.music.MusicService> { get<JellyfinService>() as app.pictureshow.core.music.MusicService }
                    single {
                        val application = this@PictureshowApplication
                        val name = deviceDisplayName(application)
                        val identity = get<DeviceIdentityStore>()
                        val http = get<HttpClient>()
                        val playbackPreferences = get<PlaybackPreferencesStore>()
                        val controller = get<RemoteControllerHost>()
                        val decoders by lazy {
                            DeviceCapabilityProbe(application).use { probe -> probe.snapshot().audioDecoders.map { it.codec }.toSet() }
                        }
                        MusicHost(
                            application,
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate),
                            get(),
                            MusicStore(application),
                            ClientMusicGateway(
                                client = { account ->
                                    app.pictureshow.core.playback.PlaybackClient(
                                        http,
                                        app.pictureshow.core.ClientIdentity(
                                            name,
                                            identity.getOrCreate(),
                                            BuildConfig.VERSION_NAME
                                        ).forAccount(account)
                                    )
                                },
                                music = get(),
                                decoders = { decoders }
                            ),
                            preferences = playbackPreferences::read,
                            remoteAttached = { controller.state.value.controller?.target?.name },
                            remoteTarget = { get<RemoteTargetHost>() }
                        )
                    }
                    single<app.pictureshow.core.UserConfigurationService> {
                        get<JellyfinService>() as app.pictureshow.core.UserConfigurationService
                    }
                    viewModel { (account: Account, session: CatalogSession) -> SearchViewModel(account, session, get()) }
                    viewModel { (account: Account, item: app.pictureshow.core.MediaItem, session: CatalogSession) ->
                        PersonViewModel(account, item, session)
                    }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { (account: Account, item: app.pictureshow.core.MediaItem) ->
                        MusicLibraryViewModel(account, item, get(), get())
                    }
                    viewModel { parameters ->
                        val account: Account = parameters.get()
                        val item: app.pictureshow.core.MediaItem = parameters.get()
                        val role: String = parameters[2]
                        val library: String = parameters[3]
                        val session: CatalogSession = parameters.get()
                        MusicDetailViewModel(
                            account,
                            item,
                            app.pictureshow.core.music.ArtistRole.entries.firstOrNull { it.name == role }
                                ?: app.pictureshow.core.music.ArtistRole.ALBUM_ARTIST,
                            library.ifBlank { null },
                            get(),
                            get(),
                            session
                        )
                    }
                    viewModel { (account: Account, items: List<String>) -> MusicPlaylistPickerViewModel(account, items, get()) }
                    viewModel { (account: Account) -> PlaybackSettingsViewModel(account, get(), get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server, saved: androidx.lifecycle.SavedStateHandle) ->
                        QuickConnectViewModel(server, get(), get(), saved)
                    }
                    viewModel { AccountsViewModel(get(), get()) }
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
        if (usesTvInterface(this)) {
            watchNext = WatchNextCoordinator(this, dependencyGraph.koin.get(), dependencyGraph.koin.get()).also { it.start() }
        }
    }
}

/** Avoid blocking Application.onCreate on a disk read just to construct the protocol client. */
internal class PersistentIdentityService(
    private val http: HttpClient,
    private val identity: DeviceIdentityStore,
    private val deviceName: String
) : JellyfinService,
    CatalogService,
    SearchService,
    app.pictureshow.core.music.MusicService,
    app.pictureshow.core.UserConfigurationService {
    private suspend fun client(deviceId: String? = null): JellyfinClient = withContext(Dispatchers.IO) {
        JellyfinClient(
            http,
            ClientIdentity(deviceName, deviceId?.takeIf(String::isNotBlank) ?: identity.getOrCreate(), BuildConfig.VERSION_NAME)
        )
    }

    private suspend fun <T> call(deviceId: String? = null, block: suspend (JellyfinClient) -> ClientResult<T>): ClientResult<T> = try {
        block(client(deviceId))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ClientResult.Failure(identityError())
    }

    override suspend fun music(account: Account, request: app.pictureshow.core.music.MusicRequest) =
        call(account.deviceId) { it.music(account, request) }

    override suspend fun instantMix(account: Account, item: String) = call(account.deviceId) { it.instantMix(account, item) }

    override suspend fun playlistCanEdit(account: Account, playlist: String) =
        runCatching { client(account.deviceId) }.getOrNull()?.playlistCanEdit(account, playlist) ?: false

    private suspend fun playlistWrite(account: Account, block: suspend (JellyfinClient) -> app.pictureshow.core.music.PlaylistWrite) =
        runCatching { client(account.deviceId) }.getOrNull()?.let { block(it) } ?: app.pictureshow.core.music.PlaylistWrite("failed")

    override suspend fun createPlaylist(account: Account, name: String, items: List<String>, isPublic: Boolean) =
        playlistWrite(account) { it.createPlaylist(account, name, items, isPublic) }

    override suspend fun renamePlaylist(account: Account, playlist: String, name: String) =
        playlistWrite(account) { it.renamePlaylist(account, playlist, name) }

    override suspend fun addToPlaylist(account: Account, playlist: String, items: List<String>) =
        playlistWrite(account) { it.addToPlaylist(account, playlist, items) }

    override suspend fun removeFromPlaylist(account: Account, playlist: String, entries: List<String>) =
        playlistWrite(account) { it.removeFromPlaylist(account, playlist, entries) }

    override suspend fun movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int) =
        playlistWrite(account) { it.movePlaylistEntry(account, playlist, entry, index) }

    override suspend fun probe(address: String) = call { it.probe(address) }
    override suspend fun publicUsers(server: Server) = call { it.publicUsers(server) }
    override suspend fun authenticate(server: Server, username: String, password: String) =
        call { client(newSignInId()).authenticate(server, username, password) }
    override suspend fun quickConnectEnabled(server: Server) = call { it.quickConnectEnabled(server) }
    override fun quickConnect(server: Server) = quickConnect(server, null) {}
    override fun quickConnect(
        server: Server,
        pending: app.pictureshow.core.PendingQuickConnect?,
        onPending: (app.pictureshow.core.PendingQuickConnect?) -> Unit
    ) = kotlinx.coroutines.flow.flow {
        val resolved = try {
            client(pending?.deviceId?.takeIf(String::isNotBlank) ?: if (pending == null) newSignInId() else null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emit(QuickConnectState.Failed(identityError()))
            return@flow
        }
        resolved.quickConnect(server, pending, onPending).collect {
            // A pre-upgrade pending code is still bound to the legacy install identity.
            emit(
                if (pending != null && pending.deviceId.isNullOrBlank() && it is QuickConnectState.Authorized) {
                    QuickConnectState.Authorized(it.account.copy(deviceId = null))
                } else {
                    it
                }
            )
        }
    }
    override suspend fun me(account: Account) = call(account.deviceId) { it.me(account) }
    override suspend fun separateDevice(account: Account) = call(account.deviceId) {
        it.renewDevice(account, java.util.UUID.randomUUID().toString())
    }
    override suspend fun logout(account: Account) = call(account.deviceId) { it.logout(account) }
    override suspend fun configuration(account: Account) = call(account.deviceId) { it.configuration(account) }
    override suspend fun saveConfiguration(account: Account, configuration: app.pictureshow.core.UserConfiguration) =
        call(account.deviceId) { it.saveConfiguration(account, configuration) }

    override suspend fun search(account: Account, query: String, kind: SearchKind) = call(account.deviceId) {
        it.search(account, query, kind)
    }
    override suspend fun personItems(account: Account, personId: String, startIndex: Int, limit: Int) =
        call(account.deviceId) { it.personItems(account, personId, startIndex, limit) }

    override suspend fun views(account: Account) = call(account.deviceId) { it.views(account) }
    override suspend fun resume(account: Account) = call(account.deviceId) { it.resume(account) }
    override suspend fun nextUp(account: Account, seriesId: String?) = call(account.deviceId) { it.nextUp(account, seriesId) }
    override suspend fun latest(account: Account, parentId: String) = call(account.deviceId) { it.latest(account, parentId) }
    override suspend fun items(account: Account, query: BrowseQuery) = call(account.deviceId) { it.items(account, query) }
    override suspend fun item(account: Account, id: String) = call(account.deviceId) { it.item(account, id) }
    override suspend fun seasons(account: Account, seriesId: String) = call(account.deviceId) { it.seasons(account, seriesId) }
    override suspend fun episodes(account: Account, seriesId: String, seasonId: String) =
        call(account.deviceId) { it.episodes(account, seriesId, seasonId) }
    override suspend fun played(account: Account, id: String, value: Boolean) = call(account.deviceId) { it.played(account, id, value) }
    override suspend fun favorite(account: Account, id: String, value: Boolean) = call(account.deviceId) { it.favorite(account, id, value) }

    private suspend fun newSignInId(): String {
        // Check identity storage before starting sign-in; legacy accounts continue to use its saved id.
        identity.getOrCreate()
        return java.util.UUID.randomUUID().toString()
    }

    private fun identityError() =
        ClientError(ErrorKind.LOCAL_STORAGE, "Could not read this device's identity. Restart Pictureshow and try again.")
}
