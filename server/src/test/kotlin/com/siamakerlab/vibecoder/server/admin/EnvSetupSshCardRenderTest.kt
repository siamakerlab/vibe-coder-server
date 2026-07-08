package com.siamakerlab.vibecoder.server.admin

import com.siamakerlab.vibecoder.server.env.ComponentState
import com.siamakerlab.vibecoder.server.env.ComponentStatus
import com.siamakerlab.vibecoder.server.env.SetupComponent
import io.kotest.matchers.string.shouldContain
import org.junit.Test

/**
 * v1.159.0 — SSH 카드가 접속 정보 + 두 provisioning 경로를 실제로 렌더하는지(HTML 출력) 검증.
 * 서비스 로직은 [SshAccessServiceTest], 여기선 사용자 가시 출력(카드 HTML)을 구동한다.
 */
class EnvSetupSshCardRenderTest {

    private fun render(sshCard: SshCardData, status: ComponentStatus): String {
        val states = SetupComponent.entries.map {
            ComponentState(it, if (it == SetupComponent.SSH_SERVER) status else ComponentStatus.INSTALLED, "msg")
        }
        return EnvSetupTemplates.envSetupPage(
            username = "admin",
            states = states,
            sshPort = 2222,
            sshCard = sshCard,
            csrf = "csrf-token",
            lang = "ko",
        )
    }

    @Test
    fun `installed card with a registered key shows connection command and forms`() {
        val html = render(
            SshCardData(
                host = "vibe.wody.work",
                port = 2222,
                authorizedKeys = listOf(AuthorizedKeyInfo("ssh-ed25519", "SHA256:ab12", "my-laptop", isAccessKey = false)),
                accessKey = null,
            ),
            ComponentStatus.INSTALLED,
        )
        html shouldContain "id=\"ssh-server\""
        html shouldContain "ssh -p 2222 vibe@vibe.wody.work"
        html shouldContain "/env-setup/ssh-server/authorized-keys/add"
        html shouldContain "/env-setup/ssh-server/access-key/generate"
        html shouldContain "SHA256:ab12"
        html shouldContain "my-laptop"
        // remove form present for the registered key
        html shouldContain "/env-setup/ssh-server/authorized-keys/remove"
    }

    @Test
    fun `card with issued access key shows download link and -i command`() {
        val html = render(
            SshCardData(
                host = "vibe.wody.work",
                port = 2200,
                authorizedKeys = listOf(AuthorizedKeyInfo("ssh-ed25519", "SHA256:cd34", "vibe-access@x", isAccessKey = true)),
                accessKey = AccessKeyInfo("SHA256:cd34", "vibe-access@x", null),
            ),
            ComponentStatus.INSTALLED,
        )
        html shouldContain "/env-setup/ssh-server/access-key/download"
        html shouldContain "ssh -i vibe-access -p 2200 vibe@vibe.wody.work"
    }

    @Test
    fun `missing server warns and defaults host placeholder`() {
        val html = render(SshCardData(), ComponentStatus.MISSING)
        // default host placeholder is html-escaped
        html shouldContain "&lt;host&gt;"
        // not-installed note present
        html shouldContain "SSH 서버가 아직 설치되지 않았습니다"
    }
}
