package io.github.xiaomeng2568.meldwise

import android.app.Application
import android.content.Context
import io.github.xiaomeng2568.meldwise.auth.*
import io.github.xiaomeng2568.meldwise.security.*
import io.github.xiaomeng2568.meldwise.network.*
import io.github.xiaomeng2568.meldwise.provider.ChatGptProvider
import io.github.xiaomeng2568.meldwise.provider.DeepSeekProvider
import io.github.xiaomeng2568.meldwise.provider.ProviderRegistry
import io.github.xiaomeng2568.meldwise.data.ChatRepository
import okhttp3.Request
import java.io.File

class MeldwiseApplication:Application() { val container by lazy { AppContainer(this) } }
/** Single app process / selected account. No dependency injection framework or spike classes. */
class AppContainer(context:Context) {
    private val directory=context.noBackupFilesDir
    private val credentialKey=AndroidKey("meldwise.credentials.v1")
    private val chatKey=AndroidKey("meldwise.chat.v1")
    private val identity=InstallationIdentity(AndroidAtomicBlob(File(directory,"installation.v1")))
    val network=NetworkClient()
    private val metadata=TrustedMetadata(network)
    private val validator=IdentityValidator(KeySetSource {
        val result=network.request(Request.Builder().url(metadata.get().jwks).get().build())
        if(result.status!=200) throw AuthFailure(AuthReason.IDENTITY_INVALID)
        result.body
    })
    private val endpoint=TokenEndpoint(network,metadata,validator)
    val tokens=TokenManager(EncryptedCredentialStore(AndroidAtomicBlob(File(directory,"credentials.v1")),
        AesGcmBox(credentialKey::get,"meldwise.credentials.v1")),endpoint)
    val oauth=OAuthCoordinator(identity::load,tokens,metadata,endpoint,validator)
    val provider=ChatGptProvider(tokens,network)
    private val deepSeekKey=AndroidKey("meldwise.deepseek.apikey.v1")
    val deepSeekCredentials=DeepSeekCredentials(AndroidAtomicBlob(File(directory,"deepseek-apikey.v1"),8192),
        AesGcmBox(deepSeekKey::get,"meldwise.deepseek.apikey.v1",8192))
    val deepSeek=DeepSeekProvider(deepSeekCredentials,network)
    val providers=ProviderRegistry(listOf(provider,deepSeek))
    private val catalogKey=AndroidKey("meldwise.catalogs.v1")
    val modelCatalogCache=io.github.xiaomeng2568.meldwise.data.ModelCatalogCache(AndroidAtomicBlob(File(directory,"catalogs.v1"),1_048_608),
        AesGcmBox(catalogKey::get,"meldwise.catalogs.v1",1_048_608))
    val chat=ChatRepository(AndroidAtomicBlob(File(directory,"chat.v1"),8_388_640),
        AesGcmBox(chatKey::get,"meldwise.chat.v1",8_388_640))
    private val compareKey=AndroidKey("meldwise.compare.v1")
    val compare=io.github.xiaomeng2568.meldwise.data.CompareRepository(AndroidAtomicBlob(File(directory,"compare.v1"),8_388_640),
        AesGcmBox(compareKey::get,"meldwise.compare.v1",8_388_640))
}
