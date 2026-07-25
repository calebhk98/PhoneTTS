package com.phonetts.app.testing

import android.os.Build
import android.os.Environment
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric 4.14.1's [org.robolectric.shadows.ShadowEnvironment] does not shadow
 * [Environment.isExternalStorageManager] at all (added in API 30) - calling it falls through to
 * the real framework method, which throws `ArrayIndexOutOfBoundsException` under Robolectric's
 * stub environment (there's no real `IStorageManager` service to answer it). That is a gap in the
 * TEST environment, not a bug in [com.phonetts.app.manage.ModelManagementScreen] - the real API
 * works fine on-device - so this custom shadow patches just that one method to a fixed `false`
 * ("no all-files access") for [com.phonetts.app.ScreenSmokeTest]'s Manage-models screen render,
 * rather than excluding that screen from the smoke test entirely.
 */
@Implements(value = Environment::class, minSdk = Build.VERSION_CODES.R)
class ShadowEnvironmentAllFilesAccess {
    companion object {
        @JvmStatic
        @Implementation
        fun isExternalStorageManager(): Boolean = false
    }
}
