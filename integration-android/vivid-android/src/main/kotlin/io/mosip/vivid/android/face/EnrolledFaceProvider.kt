package io.mosip.vivid.android.face

import android.graphics.Bitmap

/**
 * Host-app hook that returns a user's enrolled face images, already decoded.
 *
 * The registration client implements it (`io.mosip.registration_client.liveness.ArcEnrolledFaceProvider`)
 * over `UserBiometricRepository`: rows with `bmtyp_code` FACE hold the ISO/IEC 19794-5 face record that
 * operator onboarding stored (`UserOnboardService.save` -> `BIR.getBdb()`); the provider parses it with
 * `FaceBDIR` and decodes the JPEG 2000 / JPEG image. Decoding lives in the host app because the ISO parser
 * (`io.mosip.biometric.util`) and the JP2 decoder are ARC dependencies, not ours.
 *
 * If a deployment stores something we cannot decode (e.g. vendor SDK templates after
 * `IBioApiV2.extractTemplate`, which ARC 1.2.x does not do yet), [enrolledFaces] returns
 * [Enrolled.records] > 0 with no images, and matching reports `ENROLMENT_UNUSABLE` rather than
 * failing silently. A vendor matcher could then be plugged in by implementing this interface with an
 * SDK-backed comparison outside vivid.
 */
fun interface EnrolledFaceProvider {

    /** [records] = enrolled face rows found for the user; [images] = those that decoded to a bitmap. */
    class Enrolled(val records: Int, val images: List<Bitmap>)

    fun enrolledFaces(userId: String): Enrolled
}
