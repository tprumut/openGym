package ch.duartesantos.opengym.watch

import com.huawei.wearengine.WearEngineException
import com.huawei.wearengine.common.WearEngineErrorCode

data class WearFailure(val link: String, val detail: String, val backoffMs: Long? = null)

fun wearErrorCode(t: Throwable): Int? =
    (t as? WearEngineException)?.errorCode ?: (t.cause as? WearEngineException)?.errorCode

fun describeWearFailure(t: Throwable, fallback: String): WearFailure =
    when (wearErrorCode(t)) {
        WearEngineErrorCode.ERROR_CODE_UNINSTALL_HEALTH ->
            WearFailure("error", "Install Huawei Health to connect to the watch", 300_000L)
        WearEngineErrorCode.ERROR_CODE_HEALTH_LOGOUT ->
            WearFailure("auth", "Sign in to Huawei Health on the phone", 60_000L)
        WearEngineErrorCode.ERROR_CODE_USER_UNAUTHORIZED_IN_HEALTH ->
            WearFailure("auth", "Allow openGym to access the watch in Huawei Health", 60_000L)
        WearEngineErrorCode.ERROR_CODE_SCOPE_UNAUTHORIZED ->
            WearFailure("error", "Wear Engine permission is still pending approval - try again later", 300_000L)
        WearEngineErrorCode.ERROR_CODE_USER_UNAUTHORIZED_IN_WEAR_ENGINE ->
            WearFailure("auth", "Open openGym on the phone to authorize watch sync", 60_000L)
        else -> WearFailure("offline", fallback)
    }
