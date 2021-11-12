package com.example.edge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.annotation.Bean
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.retrieveMono
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.beans.PropertyVetoException

@SpringBootApplication
class EdgeApplication {


    @Bean
    fun gateway(rlb: RouteLocatorBuilder): RouteLocator {
        return rlb
            .routes {
                route {
                    path("/proxy") and host("*.spring.io")
                    filters {
                        setPath("/customers")
                        addResponseHeader(
                            HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                            "*"
                        )
                    }
                    uri("http://localhost:8080/")
                }
            }

    }

    @Bean
    fun http(wcb: WebClient.Builder) = wcb.build()

    @Bean
    fun rsocket(rsb: RSocketRequester.Builder) = rsb.tcp("localhost", 8181)
}

@Component
class CrmClient(
    private val http: WebClient,
    private val rSocket: RSocketRequester
) {

    fun customers(): Flux<Customer> =
        this.http.get().uri("http://localhost:8080/customers").retrieve()
            .bodyToFlux()

    fun profileForCustomer(customerId: Int): Mono<Profile> =
        this.rSocket.route("profiles.{cid}", customerId)
            .retrieveMono()

    suspend fun customerProfiles(): Flow<CustomerProfile> {
        val customers: Flow<Customer> = this.customers().asFlow()
        return customers.map { customer ->
            val profile: Profile =
                this.profileForCustomer(customer.id).awaitSingle()
            CustomerProfile(customer, profile)
        }
    }


}

@Controller
@ResponseBody
class CustomerRestController(private val crm: CrmClient) {

    @GetMapping("/all")
    suspend fun customerProfiles() = this.crm.customerProfiles()
}

data class CustomerProfile(val customer: Customer, val profile: Profile)

data class Customer(val id: Int, val name: String)

data class Profile(val id: Int, val customerId: Int)

fun main(args: Array<String>) {
    runApplication<EdgeApplication>(*args)
}

@Controller
class CrmGraphqlController(private val crm: CrmClient) {

    //    @SchemaMapping(typeName = "Query", field = "customers")
    @QueryMapping
    fun customers() = this.crm.customers()

    @SchemaMapping(typeName = "Customer")
    fun profile(customer: Customer) = this.crm.profileForCustomer(customer.id)



}
