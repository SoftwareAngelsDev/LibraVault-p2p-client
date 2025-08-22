package p2p.di

import org.koin.dsl.module
import p2p.helpers.ConfigurationManager
import p2p.helpers.FilePodDecoder
import p2p.helpers.FilePodEncoder
import p2p.helpers.RemotePeerReputationManager
import p2p.utils.Logger

/**
 * Main application module for dependency injection
 */
val appModule = module {
    single { Logger() }
    single { RemotePeerReputationManager(get()) }
    single { ConfigurationManager() }
    single { FilePodEncoder(get(), get(), get()) }
    single { FilePodDecoder(get(), get(), get()) }
}