package jp.kaleidpixel.pageloupe

/**
 * The pure facts about the DevTools frontend's profile, kept
 * free of Android references so the JVM tests can pin them —
 * above all that its name can never collide with the secret
 * profile's: if the two ever matched, the frontend would share
 * the secret partition, the secret wipe would take the DevTools
 * settings with it, and the frontend's own state would be
 * treated as secret data. (The bridge itself is DevToolsProfile;
 * the reasoning lives in its class docs.)
 */
object DevToolsProfilePolicy {

    /** Profile name; also the on-disk partition name. */
    const val PROFILE_NAME = "pageloupe-devtools"

    /** What an apply attempt does with a probe answer. */
    enum class ProbeCourse {
        /** Supported: go ahead and assign the profile. */
        PROCEED,

        /** Definitively unsupported: stay on the default profile, silently. */
        UNSUPPORTED,

        /**
         * The probe could not tell: the device may well be
         * supported, so the attempt counts as failed and is
         * surfaced, never as the accepted degradation.
         */
        FAILED
    }

    /** Map a multi-profile probe answer to the apply attempt's course. */
    fun probeCourse(state: ProfileSupport.SupportState): ProbeCourse =
        when (state) {
            ProfileSupport.SupportState.SUPPORTED -> ProbeCourse.PROCEED
            ProfileSupport.SupportState.UNSUPPORTED -> ProbeCourse.UNSUPPORTED
            ProfileSupport.SupportState.UNKNOWN -> ProbeCourse.FAILED
        }
}
