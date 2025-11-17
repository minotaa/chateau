package cc.minota.chateau.client

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.text.Text
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class MainClient : ClientModInitializer {
    private val LOGGER = LoggerFactory.getLogger("chataeu")
    private val httpClient = HttpClient.newHttpClient()
    private val configFile = File("config/chateau.properties")

    // Default values
    private var server = "http://aprils-macbook-pro:3000"
    private var id = "default"
    private var enabled = true
    private var dontSend = false

    override fun onInitializeClient() {
        loadConfig()
        LOGGER.info("Chateau initialized! Server: $server, ID: $id")

        // Register commands
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                literal("ct")
                    .then(literal("server")
                        .then(argument("url", StringArgumentType.greedyString())
                            .executes { context ->
                                server = StringArgumentType.getString(context, "url")
                                saveConfig()
                                context.source.sendFeedback(Text.literal("§aServer set to: $server"))
                                1
                            }
                        )
                    )
                    .then(literal("id")
                        .then(argument("id", StringArgumentType.string())
                            .executes { context ->
                                id = StringArgumentType.getString(context, "id")
                                saveConfig()
                                context.source.sendFeedback(Text.literal("§aID set to: $id"))
                                1
                            }
                        )
                    )
                    .then(literal("toggle")
                        .executes { context ->
                            enabled = !enabled
                            saveConfig()
                            val status = if (enabled) "§aenabled" else "§cdisabled"
                            context.source.sendFeedback(Text.literal("Chateau $status"))
                            1
                        }
                    )
                    .then(literal("dontsend")
                        .executes { context ->
                            dontSend = !dontSend
                            saveConfig()
                            val status = if (dontSend) "§aON (messages won't be sent to chat)" else "§cOFF (messages will be sent to chat)"
                            context.source.sendFeedback(Text.literal("Don't Send mode: $status"))
                            1
                        }
                    )
                    .then(literal("status")
                        .executes { context ->
                            val status = if (enabled) "§aenabled" else "§cdisabled"
                            val sendStatus = if (dontSend) "§cOFF (not sending to chat)" else "§aON (sending to chat)"
                            context.source.sendFeedback(Text.literal("§6Chateau Status:"))
                            context.source.sendFeedback(Text.literal("  Status: $status"))
                            context.source.sendFeedback(Text.literal("  Don't Send: $sendStatus"))
                            context.source.sendFeedback(Text.literal("  Server: §b$server"))
                            context.source.sendFeedback(Text.literal("  ID: §b$id"))
                            1
                        }
                    )
            )
        }

        // Listen for chat messages
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (enabled) {
                val hasExclamation = message.startsWith("!")
                val actualMessage = if (hasExclamation) message.substring(1) else message

                // Send to webhook
                sendWebhookAsync(actualMessage)

                // Determine if we should send to chat
                val shouldSendToChat = if (dontSend) {
                    // Don't send mode ON: only send to chat if message starts with !
                    hasExclamation
                } else {
                    // Don't send mode OFF: always send to chat (unless it has !)
                    !hasExclamation
                }

                return@register shouldSendToChat
            }
            true // Allow the message to be sent if mod is disabled
        }
    }

    private fun sendWebhookAsync(message: String) {
        thread {
            try {
                val url = "$server/input/keys/$id"
                val jsonPayload = """{"sentence":"${message.escape()}"}"""

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

                LOGGER.info("Sent to $url - Status: ${response.statusCode()}")
            } catch (e: Exception) {
                LOGGER.error("Failed to send webhook: ${e.message}")
            }
        }
    }

    private fun loadConfig() {
        if (configFile.exists()) {
            configFile.forEachLine { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    when (parts[0].trim()) {
                        "server" -> server = parts[1].trim()
                        "id" -> id = parts[1].trim()
                        "enabled" -> enabled = parts[1].trim().toBoolean()
                        "dontSend" -> dontSend = parts[1].trim().toBoolean()
                    }
                }
            }
        }
    }

    private fun saveConfig() {
        configFile.parentFile?.mkdirs()
        configFile.writeText("""
            server=$server
            id=$id
            enabled=$enabled
            dontSend=$dontSend
        """.trimIndent())
    }

    private fun String.escape() = this.replace("\\", "\\\\").replace("\"", "\\\"")
}