package p2p.helpers

import p2p.utils.LoggerInterface

class MockLogger : LoggerInterface {
    override fun debug(tag: String, message: String) {
        // No-op for testing
    }
    
    override fun info(tag: String, message: String) {
        // No-op for testing
    }
    
    override fun warn(tag: String, message: String) {
        // No-op for testing
    }
    
    override fun error(tag: String, message: String, throwable: Throwable?) {
        // No-op for testing
    }
}