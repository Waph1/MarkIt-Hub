package com.waph1.markithub.util

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.os.Bundle
import android.content.Context

class AuthenticatorService : Service() {
    private lateinit var authenticator: Authenticator

    override fun onCreate() {
        authenticator = Authenticator(this)
    }

    override fun onBind(intent: Intent): IBinder? {
        return authenticator.iBinder
    }

    class Authenticator(val context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
        
        override fun addAccount(
            response: AccountAuthenticatorResponse?,
            accountType: String?,
            authTokenType: String?,
            requiredFeatures: Array<out String>?,
            options: Bundle?
        ): Bundle {
            val intent = Intent(context, com.waph1.markithub.MainActivity::class.java)
            intent.putExtra(android.accounts.AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            val bundle = Bundle()
            bundle.putParcelable(android.accounts.AccountManager.KEY_INTENT, intent)
            return bundle
        }

        override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
        override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun getAuthTokenLabel(authTokenType: String?): String? = null
        override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle? = null
    }
}
