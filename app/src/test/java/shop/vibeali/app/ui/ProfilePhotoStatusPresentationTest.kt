package shop.vibeali.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfilePhotoStatusPresentationTest {
    @Test
    fun reviewStateIsExplainedToTheOwnerWithoutReplacingTheApprovedPhoto() {
        val presentation = profilePhotoStatusPresentation(status = "review", hasApprovedPhoto = true)

        assertEquals("Aguardando revisão", presentation.title)
        assertEquals("Sua foto atual continua visível enquanto concluímos a revisão.", presentation.detail)
    }

    @Test
    fun blockedAdultPhotoExplainsThatAnApprovedPhotoRemainsVisible() {
        val presentation = profilePhotoStatusPresentation(status = "blocked_adult", hasApprovedPhoto = true)

        assertEquals("Foto recusada por conteúdo adulto", presentation.title)
        assertEquals("Sua foto aprovada anterior continua visível. Escolha outra imagem quando quiser.", presentation.detail)
    }

    @Test
    fun pendingFirstPhotoStaysPrivateToTheOwner() {
        val presentation = profilePhotoStatusPresentation(status = "pending", hasApprovedPhoto = false)

        assertEquals("Em análise", presentation.title)
        assertEquals("Somente você consegue ver esta foto enquanto ela é analisada.", presentation.detail)
    }
}
