package com.example.demo

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest
import org.springframework.r2dbc.core.DatabaseClient
import java.util.*

@DataR2dbcTest
@Disabled
class PostRepositoryTest {

    @Autowired
    private lateinit var postRepository: PostRepository

    @Autowired
    private lateinit var client: DatabaseClient

    @Test
    fun `get all posts`() {
        val id = UUID.randomUUID()
        val inserted =
//            client.insert().into<Post>().table("posts")
//                .using(Post(title = "mytitle", content = "mycontent"))

            client.sql("insert into posts (id, title, content) values (:id, :title, :content)")
                .bind("id", id )
                .bind("title", "mytitle")
                .bind("content", "mycontent")
                .map { row, rowMetadata -> row.get(0) as Int}
                .one().block()

        println("inserted id:$inserted")

        runBlocking {
            val post = postRepository.findById(id).awaitSingle()
            assertEquals("mytitle", post?.title)
            assertEquals("mycontent", post?.content)
        }

    }

}