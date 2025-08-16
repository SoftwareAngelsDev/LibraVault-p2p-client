package p2p.utils

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class LogLevel(val value: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3)
}

object Logger {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val loggerLock = ReentrantLock()
    
    // Default configuration
    var currentLogLevel = LogLevel.INFO
    var logToConsole = true
    var logToFile = false
    
    // File logging configuration
    private var logDirectory = "logs"
    private var maxFileSizeMB = 10
    private var maxLogFileCount = 10 // Total max size = maxFileSizeMB * maxLogFileCount
    private var currentLogFile: File? = null
    private var currentFileWriter: PrintWriter? = null
    private var currentFileSize = 0L
    
    /**
     * Configure file logging
     * @param enabled Enable or disable file logging
     * @param directory Directory to store log files (default: "logs")
     * @param maxFileSizeMB Maximum size for each log file in MB (default: 10)
     * @param maxFiles Maximum number of log files to keep (default: 10)
     */
    fun configureFileLogging(
        enabled: Boolean,
        directory: String = "logs",
        maxFileSizeMB: Int = 10,
        maxFiles: Int = 10
    ) {
        loggerLock.withLock {
            // Close current file if open
            currentFileWriter?.close()
            currentFileWriter = null
            currentLogFile = null
            
            logToFile = enabled
            
            if (enabled) {
                this.logDirectory = directory
                this.maxFileSizeMB = maxFileSizeMB.coerceAtLeast(1)
                this.maxLogFileCount = maxFiles.coerceAtLeast(1)
                
                // Create log directory if it doesn't exist
                val dir = File(logDirectory)
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                
                // Clean up old logs if we have too many
                cleanupOldLogs()
                
                // Create new log file
                openNewLogFile()
            }
        }
    }
    
    fun debug(tag: String, message: String) {
        if (currentLogLevel.value <= LogLevel.DEBUG.value) {
            log(tag, message, LogLevel.DEBUG)
        }
    }
    
    fun info(tag: String, message: String) {
        if (currentLogLevel.value <= LogLevel.INFO.value) {
            log(tag, message, LogLevel.INFO)
        }
    }
    
    fun warn(tag: String, message: String) {
        if (currentLogLevel.value <= LogLevel.WARN.value) {
            log(tag, message, LogLevel.WARN)
        }
    }
    
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        if (currentLogLevel.value <= LogLevel.ERROR.value) {
            log(tag, message, LogLevel.ERROR)
            
            if (throwable != null) {
                val stackTrace = throwable.stackTraceToString()
                log(tag, "Exception: ${throwable::class.simpleName}: ${throwable.message}\n$stackTrace", LogLevel.ERROR)
            }
        }
    }
    
    private fun log(tag: String, message: String, level: LogLevel) {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        val logMessage = "[$timestamp] [${level.name}] [$tag]: $message"
        
        // Log to console if enabled
        if (logToConsole) {
            when (level) {
                LogLevel.ERROR -> System.err.println(logMessage)
                else -> println(logMessage)
            }
        }
        
        // Log to file if enabled
        if (logToFile) {
            logToFile(logMessage)
        }
    }
    
    private fun logToFile(message: String) {
        loggerLock.withLock {
            try {
                // If no file is open or file too large, rotate
                if (currentFileWriter == null || currentFileSize >= maxFileSizeMB * 1024 * 1024) {
                    rotateLogFile()
                }
                
                // Write the message
                currentFileWriter?.println(message)
                currentFileWriter?.flush()
                currentFileSize += message.length + 1 // +1 for newline
            } catch (e: Exception) {
                // Fall back to console logging if file logging fails
                System.err.println("Failed to write to log file: ${e.message}")
                e.printStackTrace(System.err)
            }
        }
    }
    
    private fun rotateLogFile() {
        currentFileWriter?.close()
        openNewLogFile()
        cleanupOldLogs()
    }
    
    private fun openNewLogFile() {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val logFileName = "p2p-client-$timestamp.log"
        
        currentLogFile = File("$logDirectory/$logFileName")
        currentFileWriter = PrintWriter(FileOutputStream(currentLogFile, true))
        currentFileSize = currentLogFile?.length() ?: 0
        
        // Log the start of a new log file
        currentFileWriter?.println("[$timestamp] [INFO] [Logger]: New log file created")
        currentFileWriter?.flush()
    }
    
    private fun cleanupOldLogs() {
        try {
            val logDir = File(logDirectory)
            if (!logDir.exists() || !logDir.isDirectory) return
            
            // Get all log files sorted by last modified (oldest first)
            val logFiles = logDir.listFiles { file -> 
                file.isFile && file.name.endsWith(".log") 
            }?.sortedBy { it.lastModified() } ?: return
            
            // Delete oldest files if we have too many
            if (logFiles.size > maxLogFileCount) {
                for (i in 0 until logFiles.size - maxLogFileCount) {
                    logFiles[i].delete()
                }
            }
        } catch (e: Exception) {
            System.err.println("Error cleaning up old log files: ${e.message}")
        }
    }
    
    fun setLogLevel(level: LogLevel) {
        currentLogLevel = level
    }
    
    // Close file handles when application shuts down
    fun shutdown() {
        loggerLock.withLock {
            currentFileWriter?.close()
            currentFileWriter = null
            currentLogFile = null
        }
    }
}
