/** Unit tests for OpaFetcher's platform-name mapping (spec §13.2): pure, no network. */
package io.explico.opa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OpaFetcherTest {

    @Test
    fun macosArm64MapsToDarwinArm64() {
        assertThat(OpaFetcher.assetNameForCurrentPlatform("Mac OS X", "aarch64")).isEqualTo("opa_darwin_arm64")
    }

    @Test
    fun macosIntelMapsToDarwinAmd64() {
        assertThat(OpaFetcher.assetNameForCurrentPlatform("Mac OS X", "x86_64")).isEqualTo("opa_darwin_amd64")
    }

    @Test
    fun linuxArm64MapsToLinuxArm64() {
        assertThat(OpaFetcher.assetNameForCurrentPlatform("Linux", "aarch64")).isEqualTo("opa_linux_arm64")
    }

    @Test
    fun linuxAmd64MapsToLinuxAmd64() {
        assertThat(OpaFetcher.assetNameForCurrentPlatform("Linux", "amd64")).isEqualTo("opa_linux_amd64")
    }

    @Test
    fun windowsAmd64MapsToWindowsExe() {
        assertThat(OpaFetcher.assetNameForCurrentPlatform("Windows 11", "amd64")).isEqualTo("opa_windows_amd64.exe")
    }

    @Test
    fun windowsArm64HasNoReleaseAndThrows() {
        assertThatThrownBy { OpaFetcher.assetNameForCurrentPlatform("Windows 11", "aarch64") }
            .isInstanceOf(UnsupportedPlatformException::class.java)
    }

    @Test
    fun unknownOsThrows() {
        assertThatThrownBy { OpaFetcher.assetNameForCurrentPlatform("PlayStation OS", "amd64") }
            .isInstanceOf(UnsupportedPlatformException::class.java)
    }

    @Test
    fun unknownArchThrows() {
        assertThatThrownBy { OpaFetcher.assetNameForCurrentPlatform("Linux", "risc-v") }
            .isInstanceOf(UnsupportedPlatformException::class.java)
    }
}
