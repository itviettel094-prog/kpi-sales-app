package com.kpi.salesapp

import android.content.Context

object SessionManager {
    private const val PREF = "session";

    fun saveLogin(ctx: Context, idToken: String, email: String) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("idToken", idToken)
            .putString("email", email)
            .apply()
    }

    fun getIdToken(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("idToken", null)

    fun getEmail(ctx: Context): String? =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("email", null)

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
