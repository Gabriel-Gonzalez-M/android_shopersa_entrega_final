# Shopersa Móvil — Android (Kotlin + XML)

Aplicación móvil de e-commerce con dos roles (Cliente y Admin). Backend, base de datos y almacenamiento de imágenes administrados **exclusivamente en Xano**. La app consume servicios REST de Xano para autenticación, usuarios, productos, carritos, órdenes y subida de imágenes.

## 1) Objetivo

- Implementar un e-commerce móvil con flujo completo:
  - Cliente: catálogo, carrito editable, pago simulado, solicitud de envío, seguimiento de órdenes.
  - Admin: CRUD de productos con múltiples imágenes, gestión de usuarios (bloqueo/desbloqueo), revisión y procesamiento de órdenes.
- Cumplir estándar profesional: estados de UI (cargando/vacío/error), confirmaciones, feedback claro, y arquitectura de red robusta (Retrofit + OkHttp + caché).

## 2) Arquitectura (Móvil + Xano)

- **Frontend móvil**: Android nativo con Kotlin y Layout XML.
- **Backend/DB/Storage**: Xano (workspaces separados para autenticación/usuarios y catálogo/ordenes).
- **Red y datos**:
  - `Retrofit` + `Gson` para REST.
  - `OkHttp` con `HttpLoggingInterceptor` (nivel controlado por build).
  - Caché HTTP en `GET` (60s) para respuestas sin headers cacheables.
  - Interceptor de autenticación que inyecta `Authorization` con token de `SharedPreferences`.
- **Estado de sesión y ruteo por rol**:
  - `TokenManager` persiste `token` y `perfil` (rol/status).
  - Navegación inicial decide entre Vista Cliente o Vista Admin usando el perfil guardado.

## 3) Requisitos

- Android Studio (Giraffe o superior recomendado).
- JDK 17 (lo gestiona Android Studio).
- Emulador o dispositivo con API 24+.
- Conexión a internet para acceder a Xano.

## 4) Abrir, compilar y ejecutar (sin pasos ocultos)

1. Abrir `Shopersa Movil` en Android Studio: `File > Open...` y seleccionar la carpeta `Shopersa Movil`.
2. Esperar el `Gradle Sync` inicial (incluye `gradlew`).
3. Ejecutar la app con el botón `Run` en un emulador/dispositivo.
4. Credenciales de prueba (Xano):
   - Admin: `admin@shopersa.cl` / `admin123`.
   - Cliente: registrar desde la app o usar una cuenta existente del entorno Xano.

También puedes generar el APK por línea de comando en Windows:

```bash
gradlew.bat assembleDebug
```

## 5) Configuración de APIs (Xano)

Las URLs de Xano están definidas mediante `BuildConfig` en `app/build.gradle.kts`. Ya están configuradas para un entorno funcional; no necesitas cambiar nada para compilar/ejecutar. Si deseas apuntar a otro workspace/ambiente, ajusta estos campos:

- `XANO_AUTH_BASE`: base del workspace de autenticación/usuarios. Se usa para:
  - `AuthService` con el sufijo `/auth`.
  - `UserService` directamente sobre `/user` (sin sufijo).
- `XANO_STORE_BASE`: base del workspace de catálogo/ordenes. Se usa para:
  - `ProductService`, `OrderService`, `CartService`, `CartItemService`, `OrderProductService`.
  - `UploadService` (subida de imágenes de productos).

Notas importantes:
- El cliente `Retrofit` normaliza el `baseUrl` para garantizar el `/` al final.
- El `OkHttpClient` cifra logs sensibles (`Authorization`, `Cookie`, `Set-Cookie`) y permite configurar nivel de logs:
  - AUTH: `BODY` (útil para diagnosticar login/signup).
  - STORE: `HEADERS` (evita imprimir binarios de imágenes).
- `XANO_TOKEN_TTL_SEC` define el tiempo de vida esperado para la sesión; la app hace chequeos y limpieza controlada.

## 6) Módulos de red (Servicios)

Implementados en `cl.shoppersa.api.RetrofitClient`:

- `authService`: login, registro, perfil (`/auth` en `XANO_AUTH_BASE`).
- `userService`: operaciones sobre usuarios (`/user` en `XANO_AUTH_BASE`).
- `productService`: productos y sus imágenes (`XANO_STORE_BASE`).
- `orderService`, `orderProductService`: órdenes y sus items (`XANO_STORE_BASE`).
- `cartService`, `cartItemService`: carrito del cliente (`XANO_STORE_BASE`).
- `uploadService`: carga de imágenes (múltiples) para productos (`XANO_STORE_BASE`).

Detalles:
- Caché GET: `public, max-age=60` tanto en request como en network interceptor para respuestas sin headers; mejora suavidad de UI.
- Interceptor de autenticación inyecta token desde `TokenManager`; los endpoints protegidos en Xano requieren `Authorization: Bearer`.

## 7) Persistencia y sesión

- `TokenManager`: almacena `token JWT` y el perfil del usuario en `SharedPreferences`. Redacción de logs evita filtrar credenciales.
- Al iniciar sesión, se guarda el rol y se rutea a:
  - Vista Cliente: catálogo, carrito, compras.
  - Vista Admin: tablero de administración (productos, usuarios, órdenes).

## 8) Lógica de la aplicación (por vistas)

### 8.1 Vista Admin

- Productos:
  - Crear/editar/buscar/listar productos.
  - Subir **múltiples imágenes** (arrastrar/ordenar según soporte del UI, y eliminar).
  - Evitar logs binarios en requests de subida; usar `HEADERS` en `OkHttp` al operar con imágenes.
- Usuarios:
  - Listado y búsqueda (cliente/admin).
  - Bloquear/desbloquear usuarios con confirmación y feedback.
  - Edición/creación de usuarios (nombre, apellidos, email, teléfono, dirección, rol, estado).
- Órdenes:
  - Revisar pendientes, aceptar (enviado) o rechazar, con estados visibles para el cliente.

Comportamiento UX:
- Estados de lista: `cargando`, `vacío`, `error` con reintento.
- Confirmaciones para acciones destructivas (bloqueo, rechazo, eliminación).
- Feedback en toasts/snackbars no invasivos.

### 8.2 Vista Cliente

- Catálogo:
  - Listar y buscar productos.
  - Ver detalle con imágenes.
- Carrito:
  - Agregar/editar/eliminar ítems.
  - Persistencia durante sesión.
- Compra:
  - Pago **simulado**.
  - Solicitud de envío.
- Órdenes:
  - Visualización de estados y progreso.

## 9) Detalle de lógica (ejemplos representativos)

### 9.1 Administración de Usuarios (`UsersAdminFragment`)

- Lista de usuarios con `RecyclerView` y `ListAdapter` (differences via `DiffUtil`).
- Filtro local por `nombre/email` con debounce (`300ms`) para evitar sobrecarga en red.
- Botón `toggle bloqueado/activo`:
  - Detecta estado actual (`status`) y confirma la acción.
  - Resuelve `id` de forma segura: si falta, intenta localizar por `email` con búsqueda flexible en Xano.
  - Actualiza backend (`status` → `"Activo"`/`"Bloqueado"`) y aplica **stale-while-revalidate** en UI:
    - Marca la fila como cargando.
    - Actualiza el item local (`status` en minúsculas para consistencia visual).
    - Invalida caché y re-sincroniza con un pequeño delay (1.5s) para suavizar.
- Creación/edición:
  - En creación, valida campos obligatorios (incluye teléfono y dirección).
  - `normalizePassword` asegura requisitos mínimos sin aleatoriedad, evitando errores de políticas de Xano.
- UI:
  - Chips:
    - `chipRole`: texto del rol, color de fondo primario.
    - `chipStatus`: texto y color basados en estado (`activo` → éxito, `bloqueado` → error) para distinguir visualmente.
  - Acciones de contacto (`btnCall`, `btnSms`) y meta (`email`).
  - Estados de fila: `progress` visible cuando la operación está en curso.

### 9.2 Productos y Múltiples Imágenes

- CRUD completo de productos en `XANO_STORE_BASE`.
- Subida múltiple con `uploadService`:
  - `multipart/form-data`, lista de archivos, referencia de `productoId`.
  - Evita impresión de cuerpos binarios en logs.
- Reordenamiento y eliminación de imágenes soportados según endpoints del workspace.

### 9.3 Carrito y Órdenes

- Carrito:
  - `cartService` y `cartItemService` gestionan creación y actualización de ítems.
  - Edición de cantidades y eliminación de productos con feedback inmediato.
- Pago simulado:
  - Genera orden y cambia estado sin transacción real (mock controlado desde Xano).
- Órdenes:
  - `orderService` y `orderProductService` exponen estados; el cliente ve el progreso.
  - Polling ligero (cada ~10s) para mantener listas coherentes sin sobrecargar Xano.

## 10) Estados de UI y manejo de errores

- Estados globales:
  - `cargando`: indicador visible.
  - `vacío`: componente dedicado.
  - `error`: mensaje explicativo y opción de reintento.
- Errores HTTP:
  - Se muestran mensajes específicos si el backend devuelve detalle (`errorBody`).
  - `Toast/Snackbar` para feedback claro y no invasivo.
- Resiliencia:
  - Búsqueda flexible de usuario por email si falta `id`.
  - Cache GET de 60s para mejorar experiencia percibida.
  - SWR tras mutaciones: actualiza UI local, invalida caché y re-sincroniza.

## 11) Seguridad

- Tokens y cookies redactados en logs; nunca se imprimen.
- Token en `SharedPreferences` y acceso mediante interceptor.
- Recomendado usar `https` en los workspaces de Xano.

## 12) Datos y demo

- Incluye datos suficientes para una demo creíble (catálogo con >10 productos y múltiples imágenes).
- Flujo sugerido (demo):
  1. Admin:
     - Login.
     - Crear producto con múltiples imágenes.
     - Listar/buscar productos.
     - Bloquear/desbloquear usuario.
     - Revisar órdenes pendientes y aceptar/rechazar.
  2. Logout.
  3. Cliente:
     - Login.
     - Navegar catálogo.
     - Agregar al carrito y editar cantidades/eliminar ítems.
     - Pago simulado y solicitud de envío.
     - Ver estado de la orden.
  4. Logout.

## 13) Estructura del proyecto (Android)
```
app/src/main/java/cl/shoppersa/
├── api/                 # RetrofitClient, AuthInterceptor, TokenManager
├── data/                # CacheStore y persistencia ligera
├── model/               # Modelos/DTOs (productos, usuarios, órdenes, carrito)
├── ui/fragments/        # Vistas Admin/Cliente (usuarios, productos, órdenes, perfil)
├── adapters/            # RecyclerView adapters de listas (productos/usuarios/órdenes)
└── utils/               # Utilidades, formatos y helpers
```

- `api/RetrofitClient`: orquesta servicios, normaliza `baseUrl`, configura `OkHttp`, logging y caché GET.
- `api/AuthInterceptor`: agrega `Authorization: Bearer <token>` desde `TokenManager`.
- `data/CacheStore`: persistencia ligera para listas (stale-while-revalidate).
- `ui/fragments`: pantallas modulares por rol y flujo (Admin/Cliente).
- `adapters`: `ListAdapter` con `DiffUtil` para render eficiente.
- `utils`: helpers varios (formatos, validaciones, normalizaciones).

## 14) Mantenimiento y configuración
- Ambientes Xano:
  - Edita `app/build.gradle.kts` y actualiza `BuildConfig.XANO_*` con las bases de tus workspaces.
  - Vuelve a sincronizar Gradle y ejecuta.
- Logs:
  - AUTH con nivel `BODY` para diagnosticar signup/login.
  - STORE con nivel `HEADERS` para evitar mostrar binarios de imágenes en logs.
- TTL de sesión:
  - `XANO_TOKEN_TTL_SEC` marca expectativas de vida del token; limpieza controlada de sesión.
- Rendimiento:
  - Caché GET de 60s y SWR tras mutaciones (UI reactiva y consistente).
- Seguridad:
  - Headers sensibles redactados en logs; usar `https` en Xano.

## 15) Criterios de aceptación (QA rápido)
- Login/Logout y ruteo por rol correcto.
- CRUD de productos con múltiples imágenes funcional.
- Carrito editable y persistente durante la sesión.
- Pago simulado y solicitud de envío visibles en órdenes.
- Gestión de usuarios: bloqueo/desbloqueo y edición/creación.
- Estados de UI: `cargando`, `vacío`, `error` con reintento.
- Confirmaciones en acciones destructivas (bloqueo, rechazo, eliminación).
- APK generable con `gradlew.bat assembleDebug`.
- README suficiente para compilar y ejecutar sin pasos ocultos.
