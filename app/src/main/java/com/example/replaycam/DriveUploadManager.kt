package com.example.replaycam

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class DriveUploadManager(private val context: Context) {

    private val tag = "DriveUploadManager"
    var lastSignInStatusCode: Int? = null
        private set
    var lastSignInErrorMessage: String? = null
        private set

    private val signInClient: GoogleSignInClient by lazy {
        val webClientId = context.getString(R.string.google_web_client_id).trim()
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE))
            .apply {
                if (webClientId.isNotBlank()) {
                    requestIdToken(webClientId)
                    requestServerAuthCode(webClientId)
                }
            }
            .build()

        GoogleSignIn.getClient(context, options)
    }

    fun authIntent(): Intent = signInClient.signInIntent

    fun parseSignInResult(data: Intent?): GoogleSignInAccount? {
        return try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            lastSignInStatusCode = null
            lastSignInErrorMessage = null
            account
        } catch (error: ApiException) {
            lastSignInStatusCode = error.statusCode
            lastSignInErrorMessage = error.localizedMessage ?: error.message
            Log.w(tag, "Falha no Google Sign-In Drive status=${error.statusCode} message=${lastSignInErrorMessage}", error)
            null
        }
    }



    fun signInErrorHint(statusCode: Int?): String {
        return when (statusCode) {
            GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Usuário cancelou a vinculação da conta Google."
            GoogleSignInStatusCodes.SIGN_IN_REQUIRED -> "Conta Google precisa de autenticação novamente."
            GoogleSignInStatusCodes.SIGN_IN_FAILED -> "Falha no Google Sign-In (12500). Verifique OAuth Web Client + usuários de teste."
            GoogleSignInStatusCodes.DEVELOPER_ERROR -> "Erro 10 (DEVELOPER_ERROR): confira packageName/SHA-1/SHA-256 no OAuth Android."
            GoogleSignInStatusCodes.NETWORK_ERROR -> "Erro de rede ao autenticar conta Google."
            null -> "Sem statusCode retornado pelo Google Sign-In."
            else -> "Falha Google Sign-In Drive. statusCode=$statusCode"
        }
    }


    fun oauthSetupChecklist(): String {
        return """
            Checklist OAuth Drive:
            1) Configure OAuth Android com packageName e SHA-1/SHA-256 do APK instalado.
            2) Configure também um OAuth Web Client e use esse client ID em google_web_client_id.
            3) Verifique se a Google Drive API está ativada no projeto.
            4) Garanta que o escopo drive.file está sendo solicitado.
            5) Na Tela de Permissão OAuth, adicione seu e-mail em Usuários de Teste (quando em modo teste).
            6) Confirme que o SHA-1 do Google Cloud é o mesmo do APK em execução (DRIVE_OAUTH_DEBUG_INFO).
            7) Se trocar ambiente/chave de build debug, atualize o SHA-1 manualmente no Google Cloud.
            8) Reinstale o app após ajustar credenciais.
        """.trimIndent()
    }

    fun oauthDebugInfo(): String {
        return runCatching {
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners?.toList().orEmpty()
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.toList().orEmpty()
            }

            val first = signatures.firstOrNull()?.toByteArray()
            val sha1 = first?.let { digestHex("SHA-1", it) } ?: "indisponível"
            val sha256 = first?.let { digestHex("SHA-256", it) } ?: "indisponível"
            "package=${context.packageName}, sha1=$sha1, sha256=$sha256"
        }.getOrElse { error ->
            "package=${context.packageName}, fingerprint_error=${error.message}"
        }
    }

    private fun digestHex(algorithm: String, bytes: ByteArray): String {
        return MessageDigest.getInstance(algorithm)
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
    }

    fun linkedAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_FILE))) account else null
    }

    fun linkedEmail(): String? = linkedAccount()?.email

    fun isLinked(): Boolean = linkedAccount() != null

    suspend fun signOut() = withContext(Dispatchers.Main) {
        signInClient.signOut()
    }

    suspend fun uploadVideo(videoUri: Uri, displayName: String, dateFolderName: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val account = linkedAccount() ?: error("Conta Google Drive não vinculada")
                val accountRef = account.account ?: error("Conta Google sem AccountManager entry")

                val credential = GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(DriveScopes.DRIVE_FILE)
                ).apply {
                    selectedAccount = accountRef
                }

                val drive = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential
                ).setApplicationName(context.getString(R.string.app_name)).build()

                val folderId = ensureDateFolder(drive, dateFolderName)

                val metadata = File().apply {
                    name = displayName
                    parents = listOf(folderId)
                    mimeType = "video/mp4"
                }

                val input = context.contentResolver.openInputStream(videoUri)
                    ?: error("Não foi possível abrir vídeo para upload")
                input.use { stream ->
                    val content = InputStreamContent("video/mp4", stream)
                    val uploaded = drive.files().create(metadata, content)
                        .setFields("id,name,webViewLink")
                        .execute()
                    uploaded.id ?: error("Upload concluído sem ID")
                }
            }
        }
    }

    private fun ensureDateFolder(drive: Drive, folderName: String): String {
        val escapedName = folderName.replace("'", "\\'")
        val query = "mimeType='application/vnd.google-apps.folder' and trashed=false and name='$escapedName'"

        val existing = drive.files().list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id,name)")
            .setPageSize(10)
            .execute()
            .files
            ?.firstOrNull()

        if (existing != null && !existing.id.isNullOrBlank()) {
            return existing.id
        }

        val folderMeta = File().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }

        val created = drive.files().create(folderMeta)
            .setFields("id")
            .execute()

        return created.id ?: error("Falha ao criar pasta no Drive")
    }
}
