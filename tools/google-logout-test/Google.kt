package com.google.android.gms.auth.api.identity
import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.common.api.Scope
class AuthorizationRequest {
 enum class Prompt { SELECT_ACCOUNT }
 class Builder {
  fun setRequestedScopes(scopes: List<Scope>) = this
  fun setPrompt(prompt: Prompt) = this
  fun build() = AuthorizationRequest()
 }
 companion object { fun builder() = Builder() }
}
class RevokeAccessRequest {
 class Builder {
  fun setScopes(scopes: List<Scope>)=this
  fun setAccount(account: Account)=this
  fun build()=RevokeAccessRequest()
 }
 companion object { fun builder()=Builder() }
}
class ClearTokenRequest {
 class Builder { fun setToken(token: String)=this; fun build()=ClearTokenRequest() }
 companion object { fun builder()=Builder() }
}
class AuthorizationResult(val accessToken: String?="synthetic-token", val resolution: Boolean=false) {
 val grantedScopes=listOf("https://www.googleapis.com/auth/youtube.force-ssl", "openid", "profile")
 val pendingIntent: PendingIntent?=PendingIntent()
 fun hasResolution()=resolution
 fun toGoogleSignInAccount()=GoogleAccount()
}
class GoogleAccount { val account=Account("synthetic-account","test") }
class Task<T>(val result: T, val isSuccessful: Boolean=true, val delayed: Boolean=false) {
 private var success: ((T)->Unit)?=null
 private var complete: ((Task<T>)->Unit)?=null
 fun addOnSuccessListener(block: (T)->Unit)=apply { success=block; if (!delayed && isSuccessful) block(result) }
 fun addOnFailureListener(block: (Exception)->Unit)=apply { if (!delayed && !isSuccessful) block(Exception("synthetic failure")) }
 fun addOnCompleteListener(block: (Task<T>)->Unit)=apply { complete=block; if (!delayed) block(this) }
 fun finish() { if (isSuccessful) success?.invoke(result); complete?.invoke(this) }
}
class AuthorizationClient {
 var authorizeCalls=0
 var next=Task(AuthorizationResult())
 var revoke=Task(Unit, isSuccessful=false)
 fun authorize(request: AuthorizationRequest): Task<AuthorizationResult> { authorizeCalls++; return next }
 fun revokeAccess(request: RevokeAccessRequest)=revoke
 fun clearToken(request: ClearTokenRequest)=Task(Unit)
 fun getAuthorizationResultFromIntent(intent: Intent)=AuthorizationResult()
}
object Identity {
 val client=AuthorizationClient()
 fun getAuthorizationClient(context: Context)=client
}
