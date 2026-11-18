package com.wynh.appapp.host

/**
 * The subapp contract level this runtime implements, compared with a subapp manifest's
 * `minimumHostVersionCode`. It belongs to the runtime rather than to an app build, so App² and
 * every standalone app built on this runtime report the same level in every build type.
 */
object HostCompatibility {
    const val LEVEL = 11
}
