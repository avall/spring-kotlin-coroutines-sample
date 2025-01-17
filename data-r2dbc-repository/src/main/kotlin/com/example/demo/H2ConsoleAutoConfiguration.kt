package com.example.demo

import org.h2.server.web.WebServer
import org.h2.tools.Server
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties
import org.springframework.boot.actuate.endpoint.web.annotation.RestControllerEndpoint
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.autoconfigure.web.ServerProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.reactive.function.server.RequestPredicates.GET
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions.route
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.rmi.server.ServerNotActiveException
import java.sql.Connection


@Component
@ConditionalOnProperty("spring.h2.console.enabled")
@EnableConfigurationProperties(H2ConsoleAutoConfiguration.Properties::class)
@RestControllerEndpoint(id = "h2console")
class H2ConsoleAutoConfiguration(
    private val h2ConsoleProperties: Properties?,
    private val serverProperties: ServerProperties,
//    private val dataSourceProperties: DataSourceProperties?,
    private val managementServerProperties: ManagementServerProperties?,
    private val managementWebEndpointProperties: WebEndpointProperties?
) {
    private val log: Logger = LoggerFactory.getLogger(javaClass)

    private var h2ConsoleServer: Server? = null
    private var h2SqlConnection: Connection? = null

    val enabled: Boolean
        get() = h2ConsoleProperties?.enabled ?: false
    val redirectUrl: String
        get() {
            return h2ConsoleProperties?.path?.let { "${serverProperties.address}/$it" }
                ?: when(managementServerProperties?.port){
                    in 1..65536 -> {
                        (managementServerProperties?.address?.toString() ?: "http://localhost").let {
                            "${it.toString()}:${managementServerProperties?.port}${managementWebEndpointProperties?.basePath ?: "/"}/h2console/redirect"
                        }
                    }
                    else -> {
                        "${serverProperties.address}:${serverProperties.port}/${managementWebEndpointProperties?.basePath}/h2console/redirect"
                    }
                }
        }
    val h2ConsoleUrl: String?
        get() {
            return h2ConsoleServer?.url
        }

    @EventListener(ContextRefreshedEvent::class)
    fun start(event: ContextRefreshedEvent) {
        if (h2ConsoleProperties?.enabled != true) {
            h2ConsoleServer = null
            return
        }
        if (h2ConsoleServer == null) {
            try {
                h2ConsoleServer =
                    Server.createWebServer("-web", "-webPort", h2ConsoleProperties.port.toString()).start()
                log.info("H2 Console started on port {}", h2ConsoleServer?.port)
                log.info("H2 {}", h2ConsoleServer?.status)
            } catch (e: Exception) {
                h2ConsoleServer?.stop()
                h2ConsoleServer = null
                log.error("H2 Console did not start {}", e)
            }
        } else {
            log.info("H2 Console is already running on port {}", h2ConsoleServer?.port)
        }
    }

    @EventListener(ContextClosedEvent::class)
    fun stop() {
        if (h2ConsoleProperties?.enabled != true) {
            return
        }
        log.info("H2 Console stopped on port ${h2ConsoleProperties.port}")
        h2SqlConnection?.close()
        h2SqlConnection = null
        h2ConsoleServer?.stop()
        h2ConsoleServer = null
    }

    @GetMapping("/")
    fun actuatorEndpoint(@RequestHeader headers: HttpHeaders): Map<String, Any?> {
        val map = HashMap<String, Any?>()
        val hostname = headers.host?.hostName
        map["status"] = if (h2ConsoleServer?.isRunning(false) == true) {
            "UP"
        } else {
            "DOWN"
        }
        map["port"] = h2ConsoleServer?.port?.toString() ?: ""
        map["dataSource"] = mapOf(Pair("url", h2ConsoleProperties?.dataSource?.url),
            Pair("username", h2ConsoleProperties?.dataSource?.username),
            Pair("password", h2ConsoleProperties?.dataSource?.password?.isNotEmpty().toString()),
            Pair("driverClassName", h2ConsoleProperties?.dataSource?.driverClassName)
        )
        map["properties"] = h2ConsoleProperties
        map["enabled"] = enabled
        map["redirectUrl"] = redirectUrl
        map["h2ConsoleUrl"] = h2ConsoleUrl

        try {
            if (h2SqlConnection?.isClosed == true || h2SqlConnection == null) {
                connectToDataSource(h2ConsoleProperties?.dataSource)
            }
            h2SqlConnection?.let {
                val sessionUri = URI((h2ConsoleServer?.service as? WebServer)?.addSession(it))
                map["sessionUri"] =
                    UriComponentsBuilder.fromUri(sessionUri).host(hostname).port(sessionUri.port).build().toString()
            }
        } catch (e: Exception) {
            log.warn("h2console endpoint exception {}", e)
        }
        return map
    }

    @GetMapping("/redirect")
    fun redirectEndpoint(@RequestHeader headers: HttpHeaders): ResponseEntity<Void> {
        val hostname = headers.host?.hostName
        val port = h2ConsoleServer?.port ?: throw ServerNotActiveException()
        if (h2SqlConnection?.isClosed == true || h2SqlConnection == null) {
            connectToDataSource(h2ConsoleProperties?.dataSource)
        }
        val h2ConsoleUri = if (h2SqlConnection?.isClosed == false) {
            val sessionUri = URI((h2ConsoleServer?.service as? WebServer)?.addSession(h2SqlConnection))
            val uri = UriComponentsBuilder.fromUri(sessionUri).host(hostname).port(sessionUri.port).build().toString()
            log.debug("H2 Console endpoint with session uri {}", uri)
            uri
        } else {
            "http://$hostname:$port/"
        }
        return ResponseEntity.status(HttpStatus.TEMPORARY_REDIRECT).header(HttpHeaders.LOCATION, h2ConsoleUri).build()
    }

    private fun connectToDataSource(dataSourceProperties: DataSourceProperties?): DriverManagerDataSource? {
        log.info("Connect to database {}",dataSourceProperties?.url)
        return dataSourceProperties?.let {
            val dataSource = DriverManagerDataSource()
            dataSource.url = dataSourceProperties.url
            dataSource.username = dataSourceProperties.username
            dataSource.password = dataSourceProperties.password
            dataSourceProperties.driverClassName?.let { dataSource.setDriverClassName(it) }
            h2SqlConnection?.close()
            if (dataSource.url == null) {
                log.info("spring.datasource.url is not found.")
                h2SqlConnection = null
                null
            } else {
                log.info("spring.datasource.url is {}",dataSource.url)
                try {
                    h2SqlConnection = dataSource.connection
                    log.info("H2 Console JDBC datasource is configured")
                    dataSource
                } catch (e: Exception) {
                    log.info("H2 Console exception {}", e)
                    h2SqlConnection?.close()
                    h2SqlConnection = null
                    null
                }
            }
        }
    }

    @Bean
    @ConditionalOnProperty("spring.h2.console.path")
    fun h2Page(): RouterFunction<ServerResponse> {
        log.info("H2 Page")
        val dataSource = connectToDataSource(h2ConsoleProperties?.dataSource)
        val pattern = h2ConsoleProperties?.path ?: throw NoSuchFieldException()
        log.info("H2 Console page {} is configured for datasource {}", pattern, dataSource?.url)
        return if (h2SqlConnection?.isClosed == false) {
            route(GET(pattern)) { request ->
                val port = h2ConsoleServer?.port ?: throw ServerNotActiveException()
                val sessionUri = URI((h2ConsoleServer?.service as? WebServer)?.addSession(h2SqlConnection))
                log.debug("H2 Console page route {} with session uri {}", pattern, sessionUri)
                val h2ConsoleUri = request.uriBuilder()
                    .port(port)
                    .replacePath(sessionUri.path)
                    .query(sessionUri.query)
                    .build()
                ServerResponse.temporaryRedirect(h2ConsoleUri).build()
            }
        } else {
            route(GET(pattern)) { request ->
                val port = h2ConsoleServer?.port ?: throw ServerNotActiveException()
                log.debug("H2 Console page route {}", pattern)
                val h2ConsoleUri = request.uriBuilder()
                    .port(port)
                    .replacePath("/")
                    .build()
                ServerResponse.temporaryRedirect(h2ConsoleUri).build()
            }
        }
    }

    @ConstructorBinding
    @ConfigurationProperties("spring.h2.console")
    data class Properties(
        val port: Int = 0,
        val enabled: Boolean = false,
        val path: String? = null,
        val dataSource: DataSourceProperties?=null
    )
}