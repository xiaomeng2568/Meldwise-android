package io.github.xiaomeng2568.meldwise.data

import io.github.xiaomeng2568.meldwise.provider.*
import io.github.xiaomeng2568.meldwise.security.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class CachedModel(val id:String,val name:String,val capabilities:Set<Capability>,val availability:ModelAvailability)
@Serializable private data class CatalogEntry(val providerId:String,val updatedAt:Long,val models:List<CachedModel>)
@Serializable private data class CatalogJournal(val version:Int=1,val entries:List<CatalogEntry> = emptyList())
class CatalogSnapshot(val providerId:String,val updatedAt:Long,val models:List<LlmModel>) {
    fun needsRefresh(now:Long)=models.isEmpty() || now<updatedAt || now-updatedAt>=MAX_AGE_MS
    override fun toString()="CatalogSnapshot(providerId=$providerId, modelCount=${models.size})"
    companion object {const val MAX_AGE_MS=86_400_000L}
}
/** Authenticated local metadata only. Never stores credentials, request bodies or provider errors. */
class ModelCatalogCache(private val blob:AtomicBlob,private val box:AesGcmBox) {
    private val json=Json {encodeDefaults=true}
    private fun read():CatalogJournal {
        val sealed=blob.read() ?: return CatalogJournal()
        require(sealed.size<=MAX_BYTES+29)
        val plain=box.open(sealed)
        try {
            require(plain.size<=MAX_BYTES)
            val journal=json.decodeFromString<CatalogJournal>(utf8(plain))
            require(journal.version==1 && journal.entries.size<=2 && journal.entries.map {it.providerId}.distinct().size==journal.entries.size)
            journal.entries.forEach {entry ->
                require(entry.providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK) && entry.updatedAt>=0)
                validateCachedModels(entry.models.map {LlmModel(it.id,it.name,"provider-catalog",ProviderCapability(it.capabilities),it.availability)})
            }
            return journal
        } finally {plain.fill(0)}
    }
    @Synchronized fun load():Map<String,CatalogSnapshot> = read().entries.associate {entry ->
        entry.providerId to CatalogSnapshot(entry.providerId,entry.updatedAt,entry.models.map {
            LlmModel(it.id,it.name,"provider-catalog",ProviderCapability(it.capabilities),it.availability)
        })
    }
    @Synchronized fun save(providerId:String,models:List<LlmModel>,updatedAt:Long) {
        require(providerId in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK) && updatedAt>=0)
        validateCachedModels(models)
        val current=read()
        write(current.copy(entries=current.entries.filterNot {it.providerId==providerId}+
            CatalogEntry(providerId,updatedAt,models.map {CachedModel(it.id,it.displayName,it.capabilities.supported,it.availability)})))
    }
    @Synchronized fun remove(providerId:String) {val current=read();write(current.copy(entries=current.entries.filterNot {it.providerId==providerId}))}
    /** An unreadable cache is disposable metadata; credentials and histories are untouched. */
    @Synchronized fun clear() {write(CatalogJournal())}
    private fun write(journal:CatalogJournal) {
        val plain=json.encodeToString(journal).toByteArray(Charsets.UTF_8)
        try {require(plain.size<=MAX_BYTES);blob.write(box.seal(plain))} finally {plain.fill(0)}
    }
    companion object {const val MAX_BYTES=1_048_576}
}
internal fun validateCachedModels(models:List<LlmModel>) {
    require(models.size<=1024 && models.map {it.id}.distinct().size==models.size)
    models.forEach {
        require(it.origin=="provider-catalog" && it.id.matches(Regex("[A-Za-z0-9._:/-]{1,128}")))
        require(it.displayName.isNotBlank() && it.displayName.length<=256 && it.displayName.none(Char::isISOControl))
        require(it.capabilities.supported.size<=Capability.entries.size)
    }
}
