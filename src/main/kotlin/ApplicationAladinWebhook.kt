//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import com.webhook.application.WebhookProcessingUseCase
import com.webhook.infrastructure.persistence.DatabaseFactory
import com.webhook.infrastructure.persistence.SqliteAccountRepository
import com.webhook.infrastructure.persistence.SqliteEventRepository
import com.webhook.infrastructure.security.HmacSignatureVerifier
import com.webhook.infrastructure.security.SignatureVerifier
import com.webhook.presentation.webhookRoutes
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.*

fun main() {
    // 1️⃣ DB 초기화
    DatabaseFactory.connect()
    DatabaseFactory.initSchema()
    println("WEBHOOK_SECRET=[" + System.getenv("WEBHOOK_SECRET") + "]")
    // 2️⃣ 의존성 구성 (Manual DI)
    val accountRepo = SqliteAccountRepository()
    val eventRepo = SqliteEventRepository()
    val useCase = WebhookProcessingUseCase(eventRepo, accountRepo)
    val verifier = HmacSignatureVerifier()

    // 3️⃣ Ktor 서버 시작
    embeddedServer(Netty, port = 8080) {
        install(ContentNegotiation) {
            json()
        }

        install(StatusPages) {
            exception<Throwable> { call, cause ->
                cause.printStackTrace()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "Internal server error")
                )
            }
        }

        routing {
            webhookRoutes(useCase, verifier)
        }
    }.start(wait = true)
}