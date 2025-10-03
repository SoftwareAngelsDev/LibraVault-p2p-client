package p2p.di

import org.koin.dsl.module
import p2p.database.config.DatabaseConfig
import p2p.database.repositories.PodsRepository
import p2p.database.repositories.RemotePeerReputationRepository
import p2p.helpers.ConfigurationManager
import p2p.helpers.FilePodDecoder
import p2p.helpers.FilePodEncoder
import p2p.helpers.RemotePeerReputationManager
import p2p.network.client.Client
import p2p.network.client.ClientTransmitter
import p2p.network.client.UdpClientTransmitter
import p2p.utils.Logger

/**
 * Main application module for dependency injection
 */
val appModule = module {
    // Core components
    single { Logger() }
    single { ConfigurationManager() }
    
    // Database components
    single { DatabaseConfig(get()) }
    single { RemotePeerReputationRepository(get(), get()) }
    single { PodsRepository(get(), get()) }
    
    // Business logic components
    single { RemotePeerReputationManager(get()) }
    single { FilePodEncoder(get(), get(), get()) }
    single { FilePodDecoder(get(), get(), get()) }
    
    // Network components  
    single { Client(get(), get(), get(), get()) }
}