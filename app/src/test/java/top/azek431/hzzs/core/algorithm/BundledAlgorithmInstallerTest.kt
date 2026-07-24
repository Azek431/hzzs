package top.azek431.hzzs.core.algorithm

import org.junit.Assert.assertEquals
import org.junit.Test
import top.azek431.hzzs.core.algorithm.BundledAlgorithmInstaller.BundledAction
import top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile
import java.io.File

class BundledAlgorithmInstallerTest {

    @Test
    fun versionCodeFromSemver_parsesCore() {
        assertEquals(1_000L, BundledAlgorithmInstaller.versionCodeFromSemver("0.1.0"))
        assertEquals(1_002L, BundledAlgorithmInstaller.versionCodeFromSemver("0.1.2"))
        assertEquals(2_001_003L, BundledAlgorithmInstaller.versionCodeFromSemver("2.1.3-beta.1"))
    }

    @Test
    fun decideBundledAction_installWhenMissing() {
        assertEquals(
            BundledAction.INSTALL,
            BundledAlgorithmInstaller.decideBundledAction(null, 1_000L),
        )
    }

    @Test
    fun decideBundledAction_upgradeBundledWhenNewer() {
        val existing = record(versionCode = 1_000L, origin = BundledAlgorithmInstaller.ORIGIN_BUNDLED)
        assertEquals(
            BundledAction.UPGRADE,
            BundledAlgorithmInstaller.decideBundledAction(existing, 1_001L),
        )
    }

    @Test
    fun decideBundledAction_skipSameBundled() {
        val existing = record(versionCode = 1_000L, origin = BundledAlgorithmInstaller.ORIGIN_BUNDLED)
        assertEquals(
            BundledAction.SKIP_SAME_OR_NEWER,
            BundledAlgorithmInstaller.decideBundledAction(existing, 1_000L),
        )
    }

    @Test
    fun decideBundledAction_legacyNullOriginTreatedAsBundled() {
        val existing = record(versionCode = 1_000L, origin = null)
        assertEquals(
            BundledAction.UPGRADE,
            BundledAlgorithmInstaller.decideBundledAction(existing, 2_000L),
        )
    }

    @Test
    fun decideBundledAction_neverOverwriteNetwork() {
        val existing = record(
            versionCode = 1_000L,
            origin = AlgorithmNetworkClient.ORIGIN_NETWORK,
        )
        assertEquals(
            BundledAction.SKIP_NETWORK,
            BundledAlgorithmInstaller.decideBundledAction(existing, 9_000_000L),
        )
    }

    private fun record(
        versionCode: Long,
        origin: String?,
    ): InstalledAlgorithmStore.InstalledAlgorithmRecord =
        InstalledAlgorithmStore.InstalledAlgorithmRecord(
            catalogId = "sea-salt-living-room-v1",
            runtimeId = "pack.sea-salt-living-room-v1",
            version = "0.1.0",
            versionCode = versionCode,
            displayName = "test",
            supportedScenes = emptySet(),
            profile = AlgorithmRuntimeProfile.builtin(),
            directory = File("."),
            installedAtEpochMs = 0L,
            originTag = origin,
        )
}
