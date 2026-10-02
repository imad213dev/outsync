package dev.outsync.sample

import android.app.Application
import dev.outsync.ConflictResolver
import dev.outsync.ConflictResolvers
import dev.outsync.SyncEngine
import dev.outsync.android.outsyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

enum class Strategy(val label: String) { Server("Server"), Client("Client"), Merge("Merge") }

class SampleApp : Application() {
    lateinit var store: NoteStore
        private set
    lateinit var cloud: CloudSimulator
        private set
    lateinit var engine: SyncEngine<Note>
        private set

    val strategy = MutableStateFlow(Strategy.Merge)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val resolvers = mapOf(
        Strategy.Server to ConflictResolvers.serverWins(),
        Strategy.Client to ConflictResolvers.clientWins(),
        Strategy.Merge to ConflictResolvers.merge<Note> { local, remote -> local.copy(body = remote.body) },
    )

    override fun onCreate() {
        super.onCreate()
        store = NoteStore(this)
        cloud = CloudSimulator(this)
        engine = outsyncEngine(
            context = this,
            name = "notes",
            adapter = NoteAdapter,
            local = store,
            remote = cloud,
            codec = NoteCodec,
            resolver = ConflictResolver { local, remote -> resolvers.getValue(strategy.value).resolve(local, remote) },
        )
    }
}
