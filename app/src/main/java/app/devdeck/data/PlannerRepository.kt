package app.devdeck.data

import app.devdeck.core.planner.PlannerData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** Calendar, todos, weekly plan, habits, deadlines, notes and boards – stored encrypted on the device. */
class PlannerRepository(private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(
        store.read(FILE)?.let { runCatching { json.decodeFromString(PlannerData.serializer(), it) }.getOrNull() } ?: PlannerData()
    )
    val data: StateFlow<PlannerData> = _data.asStateFlow()

    fun update(f: (PlannerData) -> PlannerData) {
        _data.update { f(it) }
        persist()
    }

    /** Merge data from the account (newer change per item wins). */
    fun mergeRemote(remote: PlannerData) = update { it.merge(remote).pruned() }

    private fun persist() = store.write(FILE, json.encodeToString(PlannerData.serializer(), _data.value))

    companion object { private const val FILE = "planner.json" }
}
