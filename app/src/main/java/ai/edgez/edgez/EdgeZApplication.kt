package ai.edgez.edgez

import android.app.Application
import android.util.Log
import app.organicmaps.sdk.OrganicMaps
import java.io.IOException

class EdgeZApplication : Application() {
    lateinit var organicMaps: OrganicMaps
        private set

    private var initAttempted = false
    private var initError: Throwable? = null

    override fun onCreate() {
        super.onCreate()
        organicMaps = OrganicMaps(
            applicationContext,
            "edgez",
            BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_CODE,
            BuildConfig.VERSION_NAME,
        )
    }

    fun initializeOrganicMaps(onComplete: () -> Unit = {}): Result<Boolean> {
        if (organicMaps.arePlatformAndCoreInitialized()) {
            onComplete()
            return Result.success(false)
        }
        initError?.let { return Result.failure(it) }
        if (initAttempted) return Result.success(false)

        initAttempted = true
        return try {
            Result.success(
                organicMaps.init {
                    Log.i(TAG, "Organic Maps initialized")
                    onComplete()
                },
            )
        } catch (error: IOException) {
            initError = error
            Result.failure(error)
        } catch (error: RuntimeException) {
            initError = error
            Result.failure(error)
        }
    }

    companion object {
        private const val TAG = "EdgeZOrganicMaps"
    }
}
