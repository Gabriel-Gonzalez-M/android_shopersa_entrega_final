package cl.shoppersa.api


import cl.shoppersa.BuildConfig


object ApiConfig {
    val storeBaseUrl: String = BuildConfig.XANO_STORE_BASE.trimEnd('/') + "/"
    val authBaseUrl: String = BuildConfig.XANO_AUTH_BASE.trimEnd('/') + "/"
    val tokenTtlSec: Int = BuildConfig.XANO_TOKEN_TTL_SEC
}