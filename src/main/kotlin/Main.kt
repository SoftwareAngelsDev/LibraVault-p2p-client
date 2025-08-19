import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.inject
import p2p.di.appModule
import p2p.utils.Logger
import p2p.utils.LogLevel

@Composable
@Preview
fun LoginScreen() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colors.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "LibraVault P2P Client",
                    style = MaterialTheme.typography.h4
                )
                Spacer(modifier = Modifier.height(32.dp))

                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.body2
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Button(
                    onClick = {
                        // TODO: Implement login logic
                        login(username, password)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login")
                }
            }
        }
    }
}

// Placeholder functions for login logic
fun login(username: String, password: String): Boolean {
    val logger: Logger by inject(Logger::class.java)
    logger.info("Auth", "Login attempt for user: $username")
    
    // TODO: Implement actual login logic
    val success = false
    
    if (success) {
        logger.info("Auth", "Login successful for user: $username")
    } else {
        logger.warn("Auth", "Login failed for user: $username")
    }
    
    return success
}

fun main() {
    try {
        // Start Koin
        startKoin {
            modules(appModule)
        }

        val logger: Logger by inject(Logger::class.java)
        
        // Configure logger
        logger.setLogLevel(LogLevel.DEBUG)
        
        // Setup file logging with 10MB per file and 10 files max (100MB total)
        logger.configureFileLogging(
            enabled = true,
            directory = "logs",
            maxFileSizeMB = 10,
            maxFiles = 10
        )
        
        logger.info("App", "Starting LibraVault P2P Client")
        
        application {
            Window(
                onCloseRequest = {
                    logger.info("App", "Shutting down LibraVault P2P Client")
                    logger.shutdown() // Clean up file handles
                    exitApplication()
                },
                title = "LibraVault Login"
            ) {
                LoginScreen()
            }
        }
    } catch (e: Exception) {
        // Since we can't use the logger during startup error, use System.err
        System.err.println("Fatal error during application startup: ${e.message}")
        e.printStackTrace()
        throw e
    }
}