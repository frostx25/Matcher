package shop.vibeali.app.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAlbumStorageErrorMappingTest {
    @Test
    fun recognizesNestedStorageRlsFailure() {
        val error = IllegalStateException(
            "upload failed",
            IllegalStateException("new row violates row-level security policy"),
        )

        assertTrue(error.isPrivateAlbumStorageAccessDenied())
    }

    @Test
    fun unrelatedStorageFailureIsNotReportedAsAccessDenied() {
        val error = IllegalStateException("connection closed")

        assertFalse(error.isPrivateAlbumStorageAccessDenied())
    }
}
