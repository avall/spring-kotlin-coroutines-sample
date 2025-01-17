package com.example.demo


import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.kotlin.test.test


@SpringBootTest(classes = [DemoApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationTests {

    private lateinit var client: WebClient

    @LocalServerPort
    private var port: Int = 8080

    @BeforeAll
    fun setup() {
        client = WebClient.create("http://localhost:$port")
    }

    @Test
    fun `get all posts`() {
        client.get()
                .uri("/posts")
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono { response ->
                    response.bodyToMono<ByteArray>()
                        .defaultIfEmpty(ByteArray(0))
                        .flatMap { body ->
                            ServerResponse
                                .status(response.statusCode())
                                .headers { it.addAll(response.headers().asHttpHeaders()) }
                                .bodyValue(body)
                        }
                }                .test()
                .expectNextMatches { it.statusCode() == HttpStatus.OK }
                .verifyComplete()
    }

}
