package app.pictureshow

import android.app.Application
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.preferencesDataStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.JvmServerDiscovery
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
                        val name = Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL
                        PersistentIdentityService(get(), get(), name)
                    }
                    viewModel { SessionViewModel(get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server) -> QuickConnectViewModel(server, get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                },
            )
        }
    }
}

/** Avoid blocking Application.onCreate on a disk read just to construct the protocol client. */
private class PersistentIdentityService(private val http: HttpClient, private val identity: DeviceIdentityStore, private val deviceName: String) : JellyfinService {
    private suspend fun client(): JellyfinClient = withContext(Dispatchers.IO) {
        JellyfinClient(http, ClientIdentity(deviceName, identity.getOrCreate(), BuildConfig.VERSION_NAME))
    }

    override suspend fun probe(address: String) = client().probe(address)
    override suspend fun publicUsers(server: Server) = client().publicUsers(server)
    override suspend fun authenticate(server: Server, username: String, password: String) = client().authenticate(server, username, password)
    override suspend fun quickConnectEnabled(server: Server) = client().quickConnectEnabled(server)
    override fun quickConnect(server: Server) = kotlinx.coroutines.flow.flow {
        client().quickConnect(server).collect { emit(it) }
    }
    override suspend fun me(account: Account) = client().me(account)
    override suspend fun logout(account: Account) = client().logout(account)
}
