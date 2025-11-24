# Shopersa Movil

Este es el proyecto Android funcional (Kotlin) de la app Shoppersa.

## ¿Cuál carpeta debo abrir?

- Abre únicamente la carpeta `Shopersa Movil` en Android Studio.
- Si ves otra carpeta `app/` en la raíz del repositorio, es **legado** y no forma parte del proyecto que compila. Ignórala.

## Requisitos

- Android Studio (Giraffe o superior recomendado)
- JDK 17 (Android Studio lo gestiona automáticamente)
- SDK mínimo `24`, objetivo `36`

## Cómo abrir y compilar

1. En Android Studio: `File > Open...` y selecciona la carpeta `Shopersa Movil`.
2. Espera el `Gradle Sync` inicial. El proyecto ya incluye `gradlew`.
3. Ejecuta la app con el botón `Run` seleccionando un emulador/disp. físico.

## Variantes de build

- `release`: configuración estándar (no minify, proguard básico).
- `dev`: basada en `release`, pero depurable. UID `cl.shoppersa.dev`.

## Configuración de APIs

Las URLs de Xano están definidas en `app/build.gradle.kts` vía `buildConfigField`:

- `XANO_AUTH_BASE`
- `XANO_STORE_BASE`
- `XANO_UPLOAD_BASE`

Si necesitas cambiar ambientes, ajusta esas constantes o crea un `buildType` adicional.

## Compartir el proyecto con otro compañero

1. Comprime la carpeta `Shopersa Movil` completa (incluye `gradlew`, `settings.gradle.kts`, `app/`).
2. No incluyas la carpeta raíz `app/` (legado) del repositorio.
3. Tu compañero solo debe abrir `Shopersa Movil` en Android Studio y sincronizar.

## Notas

- El proyecto usa `viewBinding` y `Retrofit` con `Gson`.
- Maneja rate-limit (HTTP 429) y estados de orden con polling de 10s.
- El `TokenManager` guarda token y perfil del usuario en `SharedPreferences`.

## Checklist de demostración (video)

Antes de grabar, verifica:

- Limpia preferencias: desde `Logout` o borra datos de la app.
- Conexión a internet estable (Xano/Backend alcanzable).
- Emulador/disp. físico con espacio para subir imágenes.
- Credenciales listas: `admin@shopersa.cl / admin123` y una cuenta cliente.

Flujo sugerido:

1. Login como **Admin**.
   - Crear producto con **múltiples imágenes**.
   - Listar/buscar productos.
   - Bloquear / desbloquear usuario (opcional).
   - Revisar **pago/orden pendiente** y **aceptar** (marcar como enviado) o **rechazar**.
2. Logout.
3. Login como **Cliente**.
   - Navegar catálogo.
   - Agregar al carrito y **editar cantidades / eliminar ítems**.
   - **Pagar (simulado)** y **solicitar envío**.
   - Ver estado del pedido en perfil / órdenes.
4. Logout.

Estados y feedback visibles durante la demo:

- `cargando`, `vacío`, `error` (con reintento) en listas.
- Confirmaciones en operaciones destructivas (bloqueo, rechazo, eliminación).
- Mensajes claros y no invasivos (toasts/snackbars).

> Nota: Si utilizas Xano en lugar del backend local, ajusta las URLs en `build.gradle.kts` (`XANO_*`) y usa las credenciales de prueba correspondientes. El flujo es el mismo.