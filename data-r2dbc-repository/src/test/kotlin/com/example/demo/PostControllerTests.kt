package com.example.demo


import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.core.publisher.Flux
import java.util.*

@WebFluxTest(
        controllers = [PostController::class],
        excludeAutoConfiguration = [ReactiveUserDetailsServiceAutoConfiguration::class, ReactiveSecurityAutoConfiguration::class]
)
class PostControllerTests {

    @Autowired
    private lateinit var client: WebTestClient

    @MockBean
    private lateinit var posts: PostRepository

    @MockBean
    private lateinit var comments: CommentRepository

    @BeforeAll
    fun setup() {
        println(">> setup testing...")
    }

    @Test
    fun `get all posts`() {
        val postsFlow = Flux.just("Post one", "Post two")
                .map {
                    Post(id= UUID.randomUUID() ,title = it, content = "content of $it")
                }
        given(posts.findAll()).willReturn(postsFlow)
        client.get()
                .uri("/posts")
                .exchange()
                .expectStatus()
                .isOk
    }

}
