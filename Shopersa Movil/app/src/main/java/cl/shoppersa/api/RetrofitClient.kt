package cl.shoppersa.api

import android.content.Context
import cl.shoppersa.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    // logBody = true para ver JSON completo (auth), false para evitar binarios (store)
    private fun okHttp(context: Context, logBody: Boolean): OkHttpClient {
        val appCtx = context.applicationContext

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                if (logBody) HttpLoggingInterceptor.Level.BODY
                else HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            // No mostrar tokens/cookies en logs
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
        }

        val auth = AuthInterceptor { TokenManager(appCtx).getToken() }

        // Cache HTTP: 20MB
        val cacheDir = java.io.File(appCtx.cacheDir, "http_cache")
        val cache = okhttp3.Cache(cacheDir, 20L * 1024 * 1024)

        return OkHttpClient.Builder()
            .cache(cache)
            .addInterceptor(logging)
            .addInterceptor(auth)
            // Forzar caché en GET si el servidor no envía headers
            .addInterceptor { chain ->
                var req = chain.request()
                if (req.method.equals("GET", ignoreCase = true)) {
                    req = req.newBuilder()
                        .header("Cache-Control", "public, max-age=60")
                        .build()
                }
                chain.proceed(req)
            }
            // Asegurar que las respuestas GET queden cacheables
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                if (chain.request().method.equals("GET", ignoreCase = true)) {
                    resp.newBuilder()
                        .header("Cache-Control", "public, max-age=60")
                        .build()
                } else {
                    resp
                }
            }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    // === Servicios ===
    // Para AUTH dejamos BODY (útil para ver por qué falla el signup/login)
    fun authService(context: Context): AuthService =
        retrofit(BuildConfig.XANO_AUTH_BASE.trimEnd('/') + "/auth", okHttp(context, logBody = true))
            .create(AuthService::class.java)

    // Para productos HEADERS (evitamos imprimir binarios de imágenes)
    fun productService(context: Context): ProductService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(ProductService::class.java)

    // Usuarios: usar base AUTH sin sufijo /auth para acceder a la tabla 'user'
    fun userService(context: Context): UserService =
        retrofit(BuildConfig.XANO_AUTH_BASE.trimEnd('/'), okHttp(context, logBody = true))
            .create(UserService::class.java)

    // Órdenes (mismo base que store)
    fun orderService(context: Context): OrderService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(OrderService::class.java)

    // Carritos
    fun cartService(context: Context): CartService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(CartService::class.java)

    // Ítems del carrito
    fun cartItemService(context: Context): CartItemService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(CartItemService::class.java)

    // Productos de la orden
    fun orderProductService(context: Context): OrderProductService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(OrderProductService::class.java)

    // Subida de imágenes (store)
    fun uploadService(context: Context): UploadService =
        retrofit(BuildConfig.XANO_STORE_BASE, okHttp(context, logBody = false))
            .create(UploadService::class.java)
}

