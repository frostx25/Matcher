package com.matcher.app.data.remote

import com.matcher.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage

object SupabaseBackend {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.startsWith("https://") &&
            BuildConfig.SUPABASE_PUBLISHABLE_KEY.startsWith("sb_publishable_")

    val client: SupabaseClient by lazy {
        check(isConfigured) {
            "Supabase development configuration is missing from local.properties"
        }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Functions)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }
}
